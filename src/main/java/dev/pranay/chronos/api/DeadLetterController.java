package dev.pranay.chronos.api;

import dev.pranay.chronos.api.dto.DeadLetterResponse;
import dev.pranay.chronos.api.dto.JobResponse;
import dev.pranay.chronos.security.TenantContext;
import dev.pranay.chronos.service.DeadLetterService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * The dead-letter queue.
 *
 * <p>A small feature that changes how the whole thing reads. Without it, a job that exhausts its
 * retries just vanishes into a FAILED status and someone has to go archaeology in the database to
 * find out what happened. With it there is a list to look at, a reason on each entry, and a button
 * to try again once the underlying problem is fixed.
 */
@RestController
@RequestMapping("/v1/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public List<DeadLetterResponse> list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return deadLetterService.list(currentTenantId(), PageRequest.of(page, Math.min(size, 100)))
                .map(DeadLetterResponse::from)
                .getContent();
    }

    @GetMapping("/{id}")
    public DeadLetterResponse get(@PathVariable String id) {
        return DeadLetterResponse.from(deadLetterService.get(currentTenantId(), id));
    }

    /**
     * Sends a dead-lettered job back out.
     *
     * <p>Returns 201 with the <em>new</em> job, because that is what was created — replay clones
     * rather than resurrecting, so the failure record stays intact and the caller gets a handle on
     * the thing that will actually run.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<JobResponse> replay(@PathVariable String id, UriComponentsBuilder uriBuilder) {
        var job = deadLetterService.replay(currentTenantId(), id);
        URI location = uriBuilder.path("/v1/jobs/{jobId}").buildAndExpand(job.getId()).toUri();
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    /** The authenticated tenant, resolved by {@code ApiKeyFilter}. */
    private String currentTenantId() {
        return TenantContext.requireId();
    }
}
