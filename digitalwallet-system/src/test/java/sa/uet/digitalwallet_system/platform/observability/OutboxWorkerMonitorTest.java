package sa.uet.digitalwallet_system.platform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import sa.uet.digitalwallet_system.platform.api.observability.OutboxWorkerObserver.EventResult;
import sa.uet.digitalwallet_system.platform.api.observability.OutboxWorkerObserver.FailureReason;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxWorkerMonitorTest {
    @Test
    void disabledWorkerIsObservableButNotReportedUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxWorkerMonitor monitor = monitor(registry, false, new MutableClock());

        assertThat(monitor.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(monitor.health().getDetails()).containsEntry("reason", "DISABLED");
        assertThat(registry.get("digitalwallet.outbox.worker.up").gauge().value()).isZero();
    }

    @Test
    void realCallbacksDriveHealthAndBoundedCounters() {
        MutableClock clock = new MutableClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxWorkerMonitor monitor = monitor(registry, true, clock);

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UNKNOWN);
        monitor.loopStarted();
        monitor.pollSucceeded();
        monitor.eventTransition(EventResult.DONE);
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("digitalwallet.outbox.worker.polls").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("digitalwallet.outbox.worker.events").tag("result", "done").counter().count()).isEqualTo(1);

        monitor.pollFailed(FailureReason.DB_ERROR);
        monitor.pollFailed(FailureReason.DB_ERROR);
        monitor.pollFailed(FailureReason.DB_ERROR);
        assertThat(monitor.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(monitor.health().getDetails()).containsEntry("reason", "DB_UNAVAILABLE");
        assertThat(registry.get("digitalwallet.outbox.worker.polls").tag("result", "db_error").counter().count()).isEqualTo(3);
    }

    @Test
    void staleOrStoppedLoopBecomesDownWithoutInventingWorkerActivity() {
        MutableClock clock = new MutableClock();
        OutboxWorkerMonitor monitor = monitor(new SimpleMeterRegistry(), true, clock);
        monitor.loopStarted();
        monitor.pollSucceeded();
        clock.advance(Duration.ofSeconds(11));
        assertThat(monitor.health().getDetails()).containsEntry("reason", "POLL_STALE");

        monitor.pollSucceeded();
        monitor.loopStopped(false);
        assertThat(monitor.health().getDetails()).containsEntry("reason", "LOOP_STOPPED");
    }

    private static OutboxWorkerMonitor monitor(SimpleMeterRegistry registry, boolean enabled, MutableClock clock) {
        return new OutboxWorkerMonitor(registry, enabled, Duration.ofSeconds(10), Duration.ofSeconds(15), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-11-24T00:00:00Z");

        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
