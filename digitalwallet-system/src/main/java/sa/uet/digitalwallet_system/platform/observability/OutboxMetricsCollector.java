package sa.uet.digitalwallet_system.platform.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public final class OutboxMetricsCollector implements SmartLifecycle {
    private final OutboxMetricsSnapshotReader reader;
    private final Duration interval;
    private final Duration freshnessTtl;
    private final Clock clock;
    private final AtomicReference<OutboxMetricsSnapshot> lastSuccessful = new AtomicReference<>();
    private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>();
    private final AtomicBoolean collecting = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public OutboxMetricsCollector(OutboxMetricsSnapshotReader reader,
                                  @Value("${app.observability.outbox.collection-interval:5s}") Duration interval,
                                  @Value("${app.observability.outbox.freshness-ttl:15s}") Duration freshnessTtl) {
        this(reader, interval, freshnessTtl, Clock.systemUTC());
    }

    OutboxMetricsCollector(OutboxMetricsSnapshotReader reader, Duration interval,
                           Duration freshnessTtl, Clock clock) {
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("collection interval must be positive");
        if (freshnessTtl.isZero() || freshnessTtl.isNegative()) throw new IllegalArgumentException("freshness TTL must be positive");
        this.reader = reader;
        this.interval = interval;
        this.freshnessTtl = freshnessTtl;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-metrics-collector");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.set(created);
        created.scheduleWithFixedDelay(this::collectSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        ScheduledExecutorService current = scheduler.getAndSet(null);
        if (current == null) return;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Outbox metrics collector did not stop within its shutdown deadline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    boolean collectNow() {
        if (!collecting.compareAndSet(false, true)) return false;
        try {
            OutboxMetricsSnapshot candidate = reader.read();
            lastSuccessful.set(candidate);
            return true;
        } catch (RuntimeException failure) {
            log.debug("Outbox metrics collection failed; retaining last successful snapshot", failure);
            return false;
        } finally {
            collecting.set(false);
        }
    }

    double value(OutboxMetric metric) {
        OutboxMetricsSnapshot snapshot = lastSuccessful.get();
        if (snapshot == null || isStale(snapshot, clock.instant())) return Double.NaN;
        return snapshot.value(metric);
    }

    OutboxMetricsSnapshot lastSuccessfulSnapshot() {
        return lastSuccessful.get();
    }

    private void collectSafely() {
        collectNow();
    }

    private boolean isStale(OutboxMetricsSnapshot snapshot, Instant now) {
        Duration age = Duration.between(snapshot.collectedAt(), now);
        return !age.isNegative() && age.compareTo(freshnessTtl) > 0;
    }
}
