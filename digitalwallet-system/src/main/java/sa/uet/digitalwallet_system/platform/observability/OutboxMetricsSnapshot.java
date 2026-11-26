package sa.uet.digitalwallet_system.platform.observability;

import java.time.Instant;
import java.util.Objects;

record OutboxMetricsSnapshot(long pending, long claimed, long dead,
                             double oldestPendingAgeSeconds, Instant collectedAt) {
    OutboxMetricsSnapshot {
        if (pending < 0 || claimed < 0 || dead < 0) {
            throw new IllegalArgumentException("outbox counts must not be negative");
        }
        if (!Double.isFinite(oldestPendingAgeSeconds) || oldestPendingAgeSeconds < 0) {
            throw new IllegalArgumentException("oldest pending age must be a finite non-negative value");
        }
        Objects.requireNonNull(collectedAt, "collectedAt");
    }

    double value(OutboxMetric metric) {
        return switch (metric) {
            case PENDING -> pending;
            case CLAIMED -> claimed;
            case DEAD -> dead;
            case OLDEST_PENDING_AGE -> oldestPendingAgeSeconds;
        };
    }
}
