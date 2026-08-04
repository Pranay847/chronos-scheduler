package dev.pranay.chronos.domain;

public enum ScheduleType {

    /** Fires once at {@code runAt}, then terminal. */
    ONE_TIME,

    /** Fires repeatedly per {@code cronExpression}, evaluated in {@code timezone}. */
    CRON
}
