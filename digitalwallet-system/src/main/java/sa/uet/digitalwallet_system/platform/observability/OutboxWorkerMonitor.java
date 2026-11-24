package sa.uet.digitalwallet_system.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.platform.api.observability.OutboxWorkerObserver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component("outboxWorker")
public final class OutboxWorkerMonitor implements OutboxWorkerObserver, HealthIndicator {
    private final boolean enabled;
    private final Duration staleAfter;
    private final Duration startupGrace;
    private final Clock clock;
    private final Instant createdAt;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<FailureReason> lastFailure = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Map<FailureReason, Counter> failureCounters = new EnumMap<>(FailureReason.class);
    private final Map<EventResult, Counter> eventCounters = new EnumMap<>(EventResult.class);
    private final Counter successfulPolls;
    private volatile boolean loopAlive;
    private volatile boolean stoppedUnexpectedly;

    @Autowired
    public OutboxWorkerMonitor(MeterRegistry registry,
                               @Value("${app.outbox.worker.enabled:false}") boolean enabled,
                               @Value("${app.outbox.worker.poll-interval:1s}") Duration pollInterval,
                               @Value("${app.outbox.worker.health-stale-after:10s}") Duration staleAfter,
                               @Value("${app.outbox.worker.health-startup-grace:15s}") Duration startupGrace) {
        this(registry, enabled, effectiveStaleAfter(pollInterval, staleAfter), startupGrace, Clock.systemUTC());
    }

    OutboxWorkerMonitor(MeterRegistry registry, boolean enabled, Duration staleAfter,
                        Duration startupGrace, Clock clock) {
        if (staleAfter.isZero() || staleAfter.isNegative()) throw new IllegalArgumentException("staleAfter must be positive");
        if (startupGrace.isNegative()) throw new IllegalArgumentException("startupGrace must not be negative");
        this.enabled = enabled;
        this.staleAfter = staleAfter;
        this.startupGrace = startupGrace;
        this.clock = clock;
        this.createdAt = clock.instant();
        this.successfulPolls = pollCounter(registry, "success");
        failureCounters.put(FailureReason.DB_ERROR, pollCounter(registry, "db_error"));
        failureCounters.put(FailureReason.UNEXPECTED_ERROR, pollCounter(registry, "unexpected_error"));
        for (EventResult result : EventResult.values()) {
            eventCounters.put(result, Counter.builder("digitalwallet.outbox.worker.events")
                    .description("Committed outbox worker event transitions")
                    .baseUnit("events")
                    .tag("result", label(result))
                    .register(registry));
        }
        Gauge.builder("digitalwallet.outbox.worker.last.success.age", this, OutboxWorkerMonitor::lastSuccessAge)
                .description("Age since the last successful outbox poll")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("digitalwallet.outbox.worker.up", this, monitor -> monitor.health().getStatus().equals(org.springframework.boot.health.contributor.Status.UP) ? 1 : 0)
                .description("Whether the outbox worker health component is up")
                .baseUnit("ratio")
                .register(registry);
    }

    @Override
    public void loopStarted() {
        loopAlive = true;
        stoppedUnexpectedly = false;
    }

    @Override
    public void loopStopped(boolean expected) {
        loopAlive = false;
        stoppedUnexpectedly = !expected;
    }

    @Override
    public void pollSucceeded() {
        lastSuccess.set(clock.instant());
        lastFailure.set(null);
        consecutiveFailures.set(0);
        successfulPolls.increment();
    }

    @Override
    public void pollFailed(FailureReason reason) {
        if (reason == null) throw new IllegalArgumentException("failure reason is required");
        lastFailure.set(reason);
        consecutiveFailures.incrementAndGet();
        failureCounters.get(reason).increment();
    }

    @Override
    public void eventTransition(EventResult result) {
        if (result == null) throw new IllegalArgumentException("event result is required");
        eventCounters.get(result).increment();
    }

    @Override
    public Health health() {
        if (!enabled) return Health.outOfService().withDetail("reason", "DISABLED").build();
        Instant now = clock.instant();
        Instant success = lastSuccess.get();
        if (stoppedUnexpectedly || (success != null && !loopAlive)) {
            return down("LOOP_STOPPED", now, success);
        }
        int failures = consecutiveFailures.get();
        if (failures >= 3) {
            return down(lastFailure.get() == FailureReason.DB_ERROR ? "DB_UNAVAILABLE" : "POLL_FAILED", now, success);
        }
        if (success == null) {
            if (Duration.between(createdAt, now).compareTo(startupGrace) <= 0) {
                return Health.unknown().withDetail("reason", "STARTING")
                        .withDetail("startupGraceSeconds", startupGrace.toSeconds()).build();
            }
            return down(loopAlive ? "POLL_STALE" : "LOOP_STOPPED", now, null);
        }
        if (Duration.between(success, now).compareTo(staleAfter) > 0) {
            return down("POLL_STALE", now, success);
        }
        return Health.up().withDetail("lastSuccessAgeSeconds", ageSeconds(now, success))
                .withDetail("staleAfterSeconds", staleAfter.toSeconds()).build();
    }

    private Health down(String reason, Instant now, Instant success) {
        Health.Builder builder = Health.down().withDetail("reason", reason)
                .withDetail("consecutiveFailures", consecutiveFailures.get())
                .withDetail("staleAfterSeconds", staleAfter.toSeconds());
        if (success != null) builder.withDetail("lastSuccessAgeSeconds", ageSeconds(now, success));
        return builder.build();
    }

    private double lastSuccessAge() {
        Instant success = lastSuccess.get();
        return success == null ? Double.NaN : Math.max(0, Duration.between(success, clock.instant()).toMillis() / 1000.0);
    }

    private static long ageSeconds(Instant now, Instant then) {
        return Math.max(0, Duration.between(then, now).toSeconds());
    }

    private static Counter pollCounter(MeterRegistry registry, String result) {
        return Counter.builder("digitalwallet.outbox.worker.polls")
                .description("Completed outbox worker poll-loop iterations")
                .baseUnit("polls")
                .tag("result", result)
                .register(registry);
    }

    private static Duration effectiveStaleAfter(Duration pollInterval, Duration configured) {
        if (pollInterval.isZero() || pollInterval.isNegative()) throw new IllegalArgumentException("pollInterval must be positive");
        Duration minimum = pollInterval.multipliedBy(3);
        return configured.compareTo(minimum) < 0 ? minimum : configured;
    }

    private static String label(EventResult result) {
        return switch (result) {
            case DONE -> "done";
            case RETRY_SCHEDULED -> "retry_scheduled";
            case DEAD -> "dead";
            case RECLAIMED -> "reclaimed";
        };
    }
}
