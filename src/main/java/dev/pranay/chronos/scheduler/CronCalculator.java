package dev.pranay.chronos.scheduler;

import dev.pranay.chronos.domain.MisfirePolicy;
import dev.pranay.chronos.domain.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Works out when a cron job fires next.
 *
 * <p>Uses Spring's {@link CronExpression} rather than adding cron-utils. It takes the same 6-field
 * syntax the plan's examples use ({@code 0 0 9 * * *}), it is already on the classpath, and —
 * the part that matters here — it advances a {@link ZonedDateTime} directly, so zone rules are
 * applied by {@code java.time} rather than reimplemented. The trade is no Quartz-only syntax
 * ({@code ?}, {@code L}, {@code W}, {@code #}); if a user ever needs those, swapping in cron-utils
 * is a change confined to this class.
 *
 * <h2>Compute in the target zone. Never in UTC.</h2>
 *
 * <p>An earlier draft of the build plan advised computing cron schedules in UTC and converting for
 * display. That is wrong, and wrong in the specific way that looks fine for months. {@code 0 0 9 *
 * * *} in {@code America/Chicago} means 09:00 <em>local</em> — 14:00 UTC in winter and 15:00 UTC in
 * summer. Precompute the series in UTC and every job silently shifts by an hour twice a year, which
 * is a worse version of the bug that advice was meant to prevent.
 *
 * <p>So the base instant is converted into the job's zone, advanced there, and converted back. The
 * stored value is always UTC; the arithmetic never is.
 *
 * <h2>What happens at a DST boundary</h2>
 *
 * <p><b>Computing in the right zone is necessary but not sufficient.</b> It is tempting to assume
 * {@code java.time} resolves the awkward cases for you. Iterating a cron expression, it does not —
 * verified, not assumed:
 *
 * <ul>
 *   <li><b>Fall back</b> — 01:30 occurs twice. {@code CronExpression.next} returns <em>both</em>,
 *       so a job scheduled in the repeated hour fires <b>twice</b> unless something stops it. That
 *       is a duplicate delivery nobody asked for, produced by the schedule rather than by any
 *       failure, and the receiver's idempotency key would not catch it because the two firings
 *       genuinely have different scheduled times.</li>
 *   <li><b>Spring forward</b> — 02:30 does not exist. {@code CronExpression.next} skips straight
 *       past to the following day, so a 02:30 daily job simply does not run that date.</li>
 * </ul>
 *
 * <p>So the policy is implemented here rather than inherited:
 *
 * <ul>
 *   <li><b>Fall back: fire once, at the first (earlier-offset) occurrence.</b> The repeat is
 *       detected by comparing local wall-clock times and skipped.</li>
 *   <li><b>Spring forward: skip.</b> The wall-clock time genuinely did not happen that day, and a
 *       job asked for 02:30 rather than "some time in the small hours". Firing it at 03:30 would
 *       be inventing an instant the user never specified. Documented and tested rather than
 *       silently inherited.</li>
 * </ul>
 *
 * <p>Erring toward "skip" on the gap and "once" on the overlap makes both boundaries
 * <em>at most once</em>, which is the right bias for something that sends webhooks.
 */
@Component
public class CronCalculator {

    private static final Logger log = LoggerFactory.getLogger(CronCalculator.class);

    /**
     * Bound on catch-up iterations when skipping past missed firings.
     *
     * <p>A per-minute job that was down for a week is ~10,000 missed slots. Without a bound, the
     * first poll after a long outage spins through them one at a time while holding a lease.
     */
    private static final int MAX_CATCH_UP_STEPS = 10_000;

    /**
     * The firing after {@code base}.
     *
     * @param base the <em>scheduled</em> time of the firing that just completed — never the time
     *             it actually ran. Rolling forward from the actual run time lets retry delay push
     *             the series, silently consuming slots: a {@code 0 asterisk/5} job whose 09:05 run
     *             succeeds on a retry at 09:06 would compute 09:10 and drop 09:05 from history
     *             with nothing to show for it.
     * @param now  used to decide whether the computed firing is already in the past
     */
    public Instant nextExecution(Schedule schedule, Instant base, Instant now) {
        CronExpression expression = parse(schedule.cronExpression());
        ZoneId zone = ZoneId.of(schedule.timezone());

        ZonedDateTime cursor = base.atZone(zone);
        ZonedDateTime next = expression.next(cursor);
        if (next == null) {
            throw new IllegalStateException(
                    "Cron expression '%s' has no further executions after %s".formatted(
                            schedule.cronExpression(), base));
        }

        next = skipDaylightSavingRepeat(expression, cursor, next);

        // On time, or the caller wants every missed slot replayed one at a time.
        if (schedule.misfirePolicy() == MisfirePolicy.FIRE_ALL || !next.toInstant().isBefore(now)) {
            return next.toInstant();
        }

        // FIRE_ONCE: the service was down (or badly behind) and several firings elapsed. Collapse
        // them into a single catch-up run at the next future slot rather than replaying the
        // backlog — an hourly report that missed six hours wants one report, not six.
        int steps = 0;
        while (next.toInstant().isBefore(now) && steps++ < MAX_CATCH_UP_STEPS) {
            ZonedDateTime candidate = expression.next(next);
            if (candidate == null) {
                return next.toInstant();
            }
            next = candidate;
        }

        if (steps > 1) {
            log.info("Cron '{}' skipped {} missed firing(s) under FIRE_ONCE; next is {}",
                    schedule.cronExpression(), steps - 1, next);
        }
        return next.toInstant();
    }

    /**
     * Drops the second half of an ambiguous hour.
     *
     * <p>When the clocks go back, a wall-clock time like 01:30 happens twice — once at the summer
     * offset and again an hour later at the winter one. {@code CronExpression} treats them as two
     * distinct occurrences and returns both, so a job in that hour would fire twice with no
     * failure anywhere to explain it.
     *
     * <p>They are told apart by their <em>local</em> time being identical while their instants
     * differ, which is only possible inside a DST overlap. When that is what we are looking at,
     * skip to the occurrence after it.
     */
    private ZonedDateTime skipDaylightSavingRepeat(CronExpression expression,
                                                   ZonedDateTime previous,
                                                   ZonedDateTime candidate) {
        boolean sameWallClockDifferentInstant =
                candidate.toLocalDateTime().equals(previous.toLocalDateTime())
                        && !candidate.toInstant().equals(previous.toInstant());

        if (!sameWallClockDifferentInstant) {
            return candidate;
        }

        ZonedDateTime after = expression.next(candidate);
        if (after == null) {
            return candidate;
        }
        log.info("Skipped a repeated {} in the DST overlap for zone {}; next is {}",
                candidate.toLocalTime(), candidate.getZone(), after);
        return after;
    }

    /**
     * Validates an expression at job-creation time.
     *
     * <p>Rejecting a bad expression on {@code POST} costs the user one clear 400. Accepting it
     * stores a job that no poller will ever fire and no error will ever explain.
     */
    public CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cronExpression is required for a CRON schedule");
        }
        try {
            return CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid cron expression '%s': %s. Expected 6 fields: second minute hour day-of-month month day-of-week"
                            .formatted(expression, e.getMessage()));
        }
    }
}
