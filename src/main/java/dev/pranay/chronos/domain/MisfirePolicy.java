package dev.pranay.chronos.domain;

/**
 * What a cron job does about firings it missed while the service was down (§4).
 *
 * <p>Borrowed from Quartz. If an hourly job was unreachable for six hours, this decides
 * whether it fires once on recovery or six times.
 */
public enum MisfirePolicy {

    /** Default. Collapse all missed firings into one, then resume the normal schedule. */
    FIRE_ONCE,

    /** Fire once per missed slot. Use only when every tick has independent meaning. */
    FIRE_ALL
}
