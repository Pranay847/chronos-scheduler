package dev.pranay.chronos.service;

import dev.pranay.chronos.api.InvalidJobException;
import dev.pranay.chronos.api.JobNotFoundException;
import dev.pranay.chronos.api.dto.CreateJobRequest;
import dev.pranay.chronos.config.ChronosProperties;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.domain.JobStatus;
import dev.pranay.chronos.domain.RetryPolicy;
import dev.pranay.chronos.domain.Schedule;
import dev.pranay.chronos.domain.ScheduleType;
import dev.pranay.chronos.domain.Target;
import dev.pranay.chronos.repository.JobRepository;
import dev.pranay.chronos.scheduler.CronCalculator;
import dev.pranay.chronos.security.InvalidTargetException;
import dev.pranay.chronos.security.SsrfGuard;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Creation and lookup of jobs.
 *
 * <p>All cross-field validation lives here rather than in annotations, because every rule below
 * depends on a second field and produces a message worth reading.
 */
@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ChronosProperties properties;
    private final ObjectMapper objectMapper;
    private final CronCalculator cronCalculator;
    private final SsrfGuard ssrfGuard;

    private static final java.util.Set<String> RESERVED_HEADERS = java.util.Set.of(
            "host", "content-length", "connection", "upgrade", "expect", "transfer-encoding");

    public JobService(JobRepository jobRepository, ChronosProperties properties,
                      ObjectMapper objectMapper, CronCalculator cronCalculator,
                      SsrfGuard ssrfGuard) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.cronCalculator = cronCalculator;
        this.ssrfGuard = ssrfGuard;
    }

    public Job create(String tenantId, CreateJobRequest request) {
        Schedule schedule = toSchedule(request.schedule());
        Instant firstRunAt = resolveFirstRunAt(schedule);
        Target target = toTarget(request.target());
        RetryPolicy retryPolicy = toRetryPolicy(request.retryPolicy());

        Job job = Job.create(tenantId, request.name(), schedule, target, retryPolicy, firstRunAt);
        return jobRepository.save(job);
    }

    public Job get(String tenantId, String id) {
        return jobRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    /**
     * Takes a job out of circulation without deleting it.
     *
     * <p>Only a PENDING job can be paused. A CLAIMED one is mid-delivery, and "pause" cannot
     * un-send a request that is already in flight — pretending otherwise would be a worse API than
     * saying no.
     */
    public Job pause(String tenantId, String id) {
        Job job = get(tenantId, id);
        if (job.getStatus() != JobStatus.PENDING) {
            throw new InvalidJobException(
                    "Only a PENDING job can be paused; this one is %s".formatted(job.getStatus()));
        }
        job.setStatus(JobStatus.PAUSED);
        job.setUpdatedAt(Instant.now());
        return jobRepository.save(job);
    }

    /**
     * Puts a paused job back in the pool.
     *
     * <p>A recurring job that was paused past its scheduled time gets rolled forward instead of
     * firing immediately on resume — otherwise un-pausing a daily job at 3pm fires the 9am
     * occurrence right then, which is not what "resume" means to anyone.
     */
    public Job resume(String tenantId, String id) {
        Job job = get(tenantId, id);
        if (job.getStatus() != JobStatus.PAUSED) {
            throw new InvalidJobException(
                    "Only a PAUSED job can be resumed; this one is %s".formatted(job.getStatus()));
        }

        Instant now = Instant.now();
        if (job.getSchedule().isCron() && job.getNextRunAt().isBefore(now)) {
            Instant next = cronCalculator.nextExecution(job.getSchedule(), job.getNextRunAt(), now);
            job.setNextRunAt(next);
            job.setCurrentRunScheduledFor(next);
        }

        job.setStatus(JobStatus.PENDING);
        job.setUpdatedAt(now);
        return jobRepository.save(job);
    }

    /**
     * Stops a job permanently.
     *
     * <p>Best-effort by design. A delivery already in flight is not aborted — the worker holding
     * the lease finishes its HTTP call, then finds the job no longer matches its conditional
     * write and discards the result. Documenting it as best-effort is honest; claiming it cancels
     * in-flight work would not be.
     */
    public Job cancel(String tenantId, String id) {
        Job job = get(tenantId, id);
        if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
            throw new InvalidJobException(
                    "Job is already %s and cannot be cancelled".formatted(job.getStatus()));
        }
        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        return jobRepository.save(job);
    }

    /**
     * Fires a job now, without touching its schedule.
     *
     * <h2>Why this creates a second job instead of moving {@code nextRunAt}</h2>
     *
     * <p>The obvious implementation is one line — set {@code nextRunAt = now} and let the poller
     * pick it up. For a one-time job that is harmless. For a recurring one it is destructive: the
     * cron rollover computes the next occurrence from {@code currentRunScheduledFor}, so faking
     * that value to "now" permanently re-bases the series. Trigger a daily 09:00 job at 14:23 and
     * it becomes a daily 14:23 job, silently and forever.
     *
     * <p>So a manual fire is a separate one-time job carrying the same target, with a
     * {@code triggeredFrom} pointer back to the original. The schedule is untouched, and the
     * manual run shows up in the audit trail as exactly what it was.
     */
    public Job trigger(String tenantId, String id) {
        Job original = get(tenantId, id);
        if (original.getStatus() == JobStatus.CANCELLED) {
            throw new InvalidJobException("A cancelled job cannot be triggered");
        }

        Instant now = Instant.now();
        Job manual = Job.create(
                original.getTenantId(),
                original.getName() + " (manual trigger)",
                new Schedule(ScheduleType.ONE_TIME, now, null, original.getSchedule().timezone(), null),
                original.getTarget(),
                original.getRetryPolicy(),
                now);
        manual.setTriggeredFrom(original.getId());

        return jobRepository.save(manual);
    }

    private Schedule toSchedule(CreateJobRequest.ScheduleRequest req) {
        String timezone = req.timezone() == null || req.timezone().isBlank() ? "UTC" : req.timezone();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidJobException("schedule.timezone '" + timezone + "' is not a known IANA zone id");
        }
        return new Schedule(req.type(), req.runAt(), req.cronExpression(), timezone, req.misfirePolicy());
    }

    /**
     * Resolves the first firing.
     *
     * <p>This value is written to <em>both</em> {@code nextRunAt} and
     * {@code currentRunScheduledFor}. They diverge later — the first retry moves
     * {@code nextRunAt} and leaves the other alone — but they must start equal, and
     * {@code currentRunScheduledFor} must never be null. A cron job whose scheduled time is
     * only populated after its first successful run has no stable identity for its first
     * firing and dereferences null on its first rollover.
     */
    private Instant resolveFirstRunAt(Schedule schedule) {
        if (schedule.type() == ScheduleType.ONE_TIME) {
            if (schedule.runAt() == null) {
                throw new InvalidJobException("schedule.runAt is required when type is ONE_TIME");
            }
            if (schedule.cronExpression() != null) {
                throw new InvalidJobException("schedule.cronExpression is not valid when type is ONE_TIME");
            }
            // A runAt in the past is allowed and fires on the next poll. That is the correct
            // reading of "run this at a time that has passed", and it keeps the catch-up
            // semantics consistent between one-time and cron jobs.
            return schedule.runAt();
        }

        if (schedule.runAt() != null) {
            throw new InvalidJobException("schedule.runAt is not valid when type is CRON");
        }

        // Compute the first occurrence now rather than on first completion. Leaving it null until
        // the job has run once means the very first rollover dereferences null, and — more subtly
        // — the first firing would have no scheduled time to derive an idempotency key from.
        try {
            return cronCalculator.nextExecution(schedule, Instant.now(), Instant.now());
        } catch (IllegalArgumentException e) {
            throw new InvalidJobException(e.getMessage());
        } catch (IllegalStateException e) {
            throw new InvalidJobException(
                    "schedule.cronExpression '%s' has no future executions".formatted(schedule.cronExpression()));
        }
    }

    private Target toTarget(CreateJobRequest.TargetRequest req) {
        // Full SSRF validation, not just syntax: every address the host resolves to is checked
        // against the denied ranges. Re-checked at delivery time too, because DNS can change in
        // between — that gap is a rebinding attack, and one check is not enough.
        try {
            ssrfGuard.validate(req.url());
        } catch (InvalidTargetException e) {
            throw new InvalidJobException(e.getMessage());
        }

        validateHeaders(req.headers());

        int timeoutMs = req.timeoutMs() == null ? properties.defaultTimeoutMs() : req.timeoutMs();
        Target target = new Target(req.url(), req.method(), req.headers(), req.payload(), timeoutMs);
        validatePayloadSize(target);
        return target;
    }

    /**
     * Rejects headers a caller must not set on their own job.
     *
     * <p>Two groups, two reasons. Ours ({@code X-Idempotency-Key}, the signature headers) would
     * otherwise let a caller shadow the values a receiver verifies against. The transport ones are
     * rejected by {@code java.net.http} with an {@code IllegalArgumentException}, so an unfiltered
     * header becomes an unhandled 500 at delivery time rather than a 400 at creation — failing
     * here means the user finds out immediately, with the offending name in the message.
     */
    private void validateHeaders(java.util.Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (String name : headers.keySet()) {
            String lower = name.toLowerCase();
            if (RESERVED_HEADERS.contains(lower) || lower.startsWith("x-webhook-")) {
                throw new InvalidJobException(
                        "target.headers may not set '%s' — it is reserved by the scheduler".formatted(name));
            }
        }
    }

    private void validatePayloadSize(Target target) {
        if (target.payload().isEmpty()) {
            return;
        }
        int bytes;
        try {
            bytes = objectMapper.writeValueAsString(target.payload()).getBytes(StandardCharsets.UTF_8).length;
        } catch (JacksonException e) {
            // Jackson 3 made these unchecked, so nothing forces this catch. It stays because
            // the payload is caller-supplied: without it a value Jackson can't serialize
            // surfaces as a 500 instead of the 400 it actually is.
            throw new InvalidJobException("target.payload is not serializable as JSON: " + e.getMessage());
        }
        if (bytes > properties.maxPayloadBytes()) {
            throw new InvalidJobException("target.payload is %d bytes, limit is %d"
                    .formatted(bytes, properties.maxPayloadBytes()));
        }
    }

    private RetryPolicy toRetryPolicy(CreateJobRequest.RetryPolicyRequest req) {
        if (req == null) {
            return RetryPolicy.DEFAULT;
        }
        RetryPolicy policy = new RetryPolicy(
                req.maxAttempts() == null ? RetryPolicy.DEFAULT.maxAttempts() : req.maxAttempts(),
                req.backoffBaseMs() == null ? RetryPolicy.DEFAULT.backoffBaseMs() : req.backoffBaseMs(),
                req.backoffMaxMs() == null ? RetryPolicy.DEFAULT.backoffMaxMs() : req.backoffMaxMs()
        );
        if (policy.backoffBaseMs() > policy.backoffMaxMs()) {
            throw new InvalidJobException("retryPolicy.backoffBaseMs (%d) exceeds backoffMaxMs (%d)"
                    .formatted(policy.backoffBaseMs(), policy.backoffMaxMs()));
        }
        return policy;
    }
}
