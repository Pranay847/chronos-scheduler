package dev.pranay.chronos.domain;

import java.time.Instant;

/**
 * When a job should fire.
 *
 * <p>Exactly one of {@code runAt} (ONE_TIME) or {@code cronExpression} (CRON) is meaningful;
 * the API layer rejects requests that supply the wrong one for their type.
 *
 * <p>{@code timezone} is load-bearing for CRON and must not be discarded. A cron expression
 * evaluated in the wrong zone is off by hours, and evaluating a zoned expression in UTC drifts
 * by an hour twice a year at DST boundaries (§4).
 */
public record Schedule(
        ScheduleType type,
        Instant runAt,
        String cronExpression,
        String timezone,
        MisfirePolicy misfirePolicy
) {

    public Schedule {
        if (misfirePolicy == null) {
            misfirePolicy = MisfirePolicy.FIRE_ONCE;
        }
        if (timezone == null || timezone.isBlank()) {
            timezone = "UTC";
        }
    }

    public boolean isCron() {
        return type == ScheduleType.CRON;
    }
}
