package dev.pranay.chronos.api;

import dev.pranay.chronos.api.dto.CreateJobRequest;
import dev.pranay.chronos.api.dto.JobResponse;
import dev.pranay.chronos.domain.Job;
import dev.pranay.chronos.security.RateLimiter;
import dev.pranay.chronos.security.TenantContext;
import dev.pranay.chronos.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Job creation and lookup.
 *
 * <p>Phase 1 covers only these two. Cancel, pause, resume, trigger, and the execution history
 * arrive with the machinery they describe — an endpoint that pauses a scheduler that doesn't
 * run yet would be a lie in the OpenAPI document.
 */
@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private final JobService jobService;
    private final RateLimiter rateLimiter;

    public JobController(JobService jobService, RateLimiter rateLimiter) {
        this.jobService = jobService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request,
                                              UriComponentsBuilder uriBuilder) {
        var tenant = TenantContext.require();
        if (!rateLimiter.tryConsume(tenant.getId(), tenant.getJobsPerMinute())) {
            throw new RateLimitExceededException(tenant.getJobsPerMinute());
        }
        Job job = jobService.create(currentTenantId(), request);
        URI location = uriBuilder.path("/v1/jobs/{id}").buildAndExpand(job.getId()).toUri();
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable String id) {
        return JobResponse.from(jobService.get(currentTenantId(), id));
    }

    @PostMapping("/{id}/pause")
    public JobResponse pause(@PathVariable String id) {
        return JobResponse.from(jobService.pause(currentTenantId(), id));
    }

    @PostMapping("/{id}/resume")
    public JobResponse resume(@PathVariable String id) {
        return JobResponse.from(jobService.resume(currentTenantId(), id));
    }

    /**
     * Fires the job now.
     *
     * <p>Returns <b>201 with a new job</b>, not 200 with the existing one, because that is
     * literally what happens: a manual fire is a separate one-time job so that triggering a
     * recurring job cannot re-base its schedule. The response body is the job that will actually
     * run.
     */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<JobResponse> trigger(@PathVariable String id, UriComponentsBuilder uriBuilder) {
        Job manual = jobService.trigger(currentTenantId(), id);
        URI location = uriBuilder.path("/v1/jobs/{jobId}").buildAndExpand(manual.getId()).toUri();
        return ResponseEntity.created(location).body(JobResponse.from(manual));
    }

    /** Cancels a job. Best-effort: a delivery already in flight is not aborted. */
    @DeleteMapping("/{id}")
    public JobResponse cancel(@PathVariable String id) {
        return JobResponse.from(jobService.cancel(currentTenantId(), id));
    }

    /**
     * The authenticated tenant, resolved by {@code ApiKeyFilter} from the bearer token.
     *
     * <p>Was a fixed constant through Phases 1-5. Everything downstream already took a tenant id
     * as a parameter, so switching to real authentication changed where this value comes from and
     * nothing else — which was the point of never reading it from an unauthenticated header.
     */
    private String currentTenantId() {
        return TenantContext.requireId();
    }
}
