package dev.pranay.chronos.api.dto;

import dev.pranay.chronos.domain.MisfirePolicy;
import dev.pranay.chronos.domain.ScheduleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * Request body for {@code POST /v1/jobs}.
 *
 * <p>Bean Validation covers shape only — presence, ranges, string lengths. The rules that
 * depend on other fields (a ONE_TIME schedule needing {@code runAt}, a CRON schedule needing
 * an expression and a resolvable zone) live in the service, where the failure can carry a
 * message that names the actual problem.
 */
public record CreateJobRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        @NotNull
        @Valid
        ScheduleRequest schedule,

        @NotNull
        @Valid
        TargetRequest target,

        @Valid
        RetryPolicyRequest retryPolicy
) {

    public record ScheduleRequest(

            @NotNull
            ScheduleType type,

            /** Required for ONE_TIME, ignored otherwise. */
            Instant runAt,

            /** Required for CRON, ignored otherwise. */
            @Size(max = 120)
            String cronExpression,

            /** IANA zone id. Defaults to UTC. Meaningful only for CRON. */
            @Size(max = 64)
            String timezone,

            MisfirePolicy misfirePolicy
    ) {}

    public record TargetRequest(

            @NotBlank
            @Size(max = 2048)
            String url,

            @Size(max = 10)
            String method,

            Map<String, String> headers,

            Map<String, Object> payload,

            /**
             * Per-request timeout. Bounded on both ends: too low and nothing ever succeeds,
             * too high and a single hanging endpoint outlives its lease and gets its job
             * redelivered while the first attempt is still open (§3).
             */
            @Min(100)
            @Max(60_000)
            Integer timeoutMs
    ) {}

    public record RetryPolicyRequest(

            @Min(1)
            @Max(20)
            Integer maxAttempts,

            @Min(100)
            @Max(3_600_000)
            Long backoffBaseMs,

            @Min(1_000)
            @Max(86_400_000)
            Long backoffMaxMs
    ) {}
}
