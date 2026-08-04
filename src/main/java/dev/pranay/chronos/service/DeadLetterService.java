package dev.pranay.chronos.service;

import dev.pranay.chronos.api.JobNotFoundException;
import dev.pranay.chronos.domain.DeadLetter;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.repository.DeadLetterRepository;
import dev.pranay.chronos.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository deadLetterRepository;
    private final JobRepository jobRepository;

    public DeadLetterService(DeadLetterRepository deadLetterRepository, JobRepository jobRepository) {
        this.deadLetterRepository = deadLetterRepository;
        this.jobRepository = jobRepository;
    }

    public Page<DeadLetter> list(String tenantId, Pageable pageable) {
        return deadLetterRepository.findByTenantIdOrderByFailedAtDesc(tenantId, pageable);
    }

    public DeadLetter get(String tenantId, String id) {
        return deadLetterRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    /**
     * Sends a dead-lettered job back out as a fresh job.
     *
     * <h2>Why a clone, and why a new idempotency key</h2>
     *
     * <p>The failed job is left exactly as it died. Replay builds a <em>new</em> job from the
     * snapshot, so the historical record of the failure stays intact and the dead letter keeps a
     * pointer to what it became.
     *
     * <p>The consequential part is that the new job gets a new {@code currentRunScheduledFor}, and
     * therefore a new idempotency key. That is the opposite of what the retry path does, and
     * deliberately so. Automatic retries are the system saying "the same firing, again" — the
     * receiver should deduplicate those. A replay is a human saying "send this now", usually after
     * fixing whatever broke. If it reused the original key, a receiver that had in fact processed
     * the original would silently drop it, and the operator would see a successful replay with no
     * effect at the other end — the worst possible outcome for a button whose entire purpose is
     * "make this happen".
     *
     * <p>The cost of that choice is that replaying a job the receiver <em>did</em> process
     * delivers it twice. That is the right trade: it is visible, it is the operator's explicit
     * decision, and at-least-once already asks receivers to tolerate duplicates.
     */
    public Job replay(String tenantId, String deadLetterId) {
        DeadLetter letter = get(tenantId, deadLetterId);

        if (letter.isReplayed()) {
            throw new IllegalStateException(
                    "Dead letter %s was already replayed at %s as job %s"
                            .formatted(deadLetterId, letter.getReplayedAt(), letter.getReplayJobId()));
        }

        Job snapshot = letter.getJobSnapshot();
        Instant now = Instant.now();

        // attempt and claimCount start at zero: a replay gets a full budget, not the remains of
        // the one it exhausted.
        Job replayed = Job.create(
                snapshot.getTenantId(),
                snapshot.getName(),
                snapshot.getSchedule(),
                snapshot.getTarget(),
                snapshot.getRetryPolicy(),
                now);

        Job saved = jobRepository.save(replayed);
        letter.markReplayed(saved.getId());
        deadLetterRepository.save(letter);

        log.info("Replayed dead letter {} (original job {}) as new job {}",
                deadLetterId, letter.getJobId(), saved.getId());
        return saved;
    }
}
