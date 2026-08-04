package dev.pranay.chronos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduling and dispatch wiring.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * Virtual threads for webhook delivery.
     *
     * <p>Delivery is almost entirely waiting on someone else's server, which is the textbook case
     * for virtual threads: a platform-thread pool sized for this workload would be mostly idle
     * threads holding a megabyte of stack each, and its size would become a hard ceiling on
     * concurrent deliveries. Virtual threads move that ceiling to memory rather than thread count,
     * so a small container can hold thousands of in-flight requests.
     *
     * <p>What virtual threads do <em>not</em> remove is the reason to bound concurrency here: a
     * claimed job's lease is already running, so unbounded dispatch means unbounded queue wait
     * inside the lease window. {@code PollerService} caps it via {@code chronos.max-in-flight}.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService dispatcherExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Per-worker poll offset, in milliseconds.
     *
     * <p>Every worker runs the same 200ms loop, and {@code docker compose up} starts them within
     * milliseconds of each other — so without an offset the whole fleet wakes at the same instant,
     * issues the same claim query, and N-1 of them lose the race. That shows up as
     * {@code scheduler.claim.contention} and as wasted database round-trips that scale with worker
     * count.
     *
     * <p>Randomising the <em>initial</em> delay is enough. With {@code fixedDelay} each worker's
     * period is its own work time plus the delay, so once they start out of phase they stay
     * decorrelated on their own.
     */
    @Bean("pollJitterMs")
    public String pollJitterMs(ChronosProperties properties) {
        long interval = Math.max(1, properties.pollIntervalMs());
        return String.valueOf(ThreadLocalRandom.current().nextLong(0, interval));
    }

    /**
     * A scheduler used only by the change-stream wakeup.
     *
     * <p>Separate from {@link #taskScheduler()} because sharing them is actively harmful. The main
     * scheduler runs the 200ms poller, the reaper and the depth gauge on four threads; a burst of
     * inserts queues one wakeup task per job onto it, and those tasks then sit in front of the very
     * poll cycle they were meant to accelerate. The measured result of getting this wrong was drift
     * roughly 12x WORSE than plain polling — an optimisation that made the system slower by
     * starving its own baseline.
     */
    @Bean("wakeupScheduler")
    public ThreadPoolTaskScheduler wakeupScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("chronos-wake-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(2);
        // Drop wakeups rather than queue them without bound: a wakeup that runs late is worthless,
        // because the poller has already collected the job by then.
        scheduler.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        return scheduler;
    }

    /**
     * More than one scheduler thread.
     *
     * <p>Spring's default {@code TaskScheduler} pool size is <b>1</b>, and this service has three
     * things on {@code @Scheduled}: the 200ms poller, the 10s reaper, and the 10s depth gauge. On
     * a single thread they queue behind each other, so a poll cycle that blocks — a slow claim
     * query under load, say — delays the reaper, which is the one component whose whole job is to
     * notice that something is stuck. Exactly backwards.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("chronos-sched-");
        // Cancel repeating tasks rather than waiting for them. Everything on this scheduler is
        // periodic, so "wait for tasks to complete" means waiting for a loop that reschedules
        // itself — it never completes, and shutdown just burns the full await timeout every time.
        // The work that genuinely must finish before exit is handled by PollerService#shutdown.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
