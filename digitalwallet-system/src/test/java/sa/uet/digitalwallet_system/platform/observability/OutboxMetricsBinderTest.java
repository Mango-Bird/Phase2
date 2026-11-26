package sa.uet.digitalwallet_system.platform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMetricsBinderTest {
    @Test
    void repeatedGaugeReadsUseOnlyTheAtomicMemorySnapshot() {
        MutableClock clock = new MutableClock();
        AtomicInteger databaseReads = new AtomicInteger();
        OutboxMetricsCollector collector = collector(() -> {
            databaseReads.incrementAndGet();
            return snapshot(3, 2, 1, 12.5, clock);
        }, clock);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(collector.collectNow()).isTrue();
        new OutboxMetricsBinder(collector).bindTo(registry);

        for (int scrape = 0; scrape < 100; scrape++) {
            assertThat(value(registry, "pending")).isEqualTo(3);
            assertThat(value(registry, "claimed")).isEqualTo(2);
            assertThat(value(registry, "dead")).isEqualTo(1);
            assertThat(registry.get("digitalwallet.outbox.oldest.pending.age").gauge().value()).isEqualTo(12.5);
        }
        assertThat(databaseReads).hasValue(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("status")));
    }

    @Test
    void missingStaleAndFailedSnapshotsNeverBecomeFabricatedZeroes() {
        MutableClock clock = new MutableClock();
        AtomicReference<Supplier<OutboxMetricsSnapshot>> next = new AtomicReference<>(
                () -> snapshot(7, 6, 5, 4.5, clock));
        OutboxMetricsCollector collector = collector(() -> next.get().get(), clock);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxMetricsBinder(collector).bindTo(registry);

        assertThat(value(registry, "pending")).isNaN();
        assertThat(collector.collectNow()).isTrue();
        Instant firstCollectedAt = collector.lastSuccessfulSnapshot().collectedAt();
        clock.advance(Duration.ofSeconds(10));
        next.set(() -> { throw new DataAccessResourceFailureException("offline"); });
        assertThat(collector.collectNow()).isFalse();
        assertThat(collector.lastSuccessfulSnapshot().collectedAt()).isEqualTo(firstCollectedAt);
        assertThat(value(registry, "pending")).isEqualTo(7);

        clock.advance(Duration.ofSeconds(6));
        assertThat(value(registry, "pending")).isNaN();
        assertThat(registry.get("digitalwallet.outbox.oldest.pending.age").gauge().value()).isNaN();

        next.set(() -> snapshot(2, 1, 0, 0.25, clock));
        assertThat(collector.collectNow()).isTrue();
        assertThat(value(registry, "pending")).isEqualTo(2);
        assertThat(collector.lastSuccessfulSnapshot().collectedAt()).isEqualTo(clock.instant());
    }

    @Test
    void overlappingCollectionsAreRejectedAndPublishOnlyCompleteSnapshots() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        OutboxMetricsCollector collector = collector(() -> {
            reads.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return snapshot(11, 22, 33, 44, clock);
        }, clock);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(collector::collectNow);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(collector.collectNow()).isFalse();
            assertThat(collector.lastSuccessfulSnapshot()).isNull();
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(reads).hasValue(1);
        assertThat(collector.lastSuccessfulSnapshot()).isEqualTo(snapshot(11, 22, 33, 44, clock));
    }

    private static OutboxMetricsCollector collector(OutboxMetricsSnapshotReader reader, MutableClock clock) {
        return new OutboxMetricsCollector(reader, Duration.ofSeconds(5), Duration.ofSeconds(15), clock);
    }

    private static OutboxMetricsSnapshot snapshot(long pending, long claimed, long dead,
                                                   double age, MutableClock clock) {
        return new OutboxMetricsSnapshot(pending, claimed, dead, age, clock.instant());
    }

    private static double value(SimpleMeterRegistry registry, String status) {
        return registry.get("digitalwallet.outbox.events").tag("status", status).gauge().value();
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-11-25T00:00:00Z");

        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
