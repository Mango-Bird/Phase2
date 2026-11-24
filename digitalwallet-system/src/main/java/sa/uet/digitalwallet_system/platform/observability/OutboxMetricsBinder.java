package sa.uet.digitalwallet_system.platform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class OutboxMetricsBinder implements MeterBinder {
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM outbox_events WHERE status = ?";
    private static final String OLDEST_PENDING_SQL = """
            SELECT COALESCE(GREATEST(
                TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)) / 1000000.0,
                0
            ), 0)
            FROM outbox_events
            WHERE status = 'PENDING'
            """;

    private final JdbcTemplate jdbc;

    public OutboxMetricsBinder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        bindStatus(registry, "PENDING", "pending");
        bindStatus(registry, "CLAIMED", "claimed");
        bindStatus(registry, "DEAD", "dead");
        Gauge.builder("digitalwallet.outbox.oldest.pending.age", this, OutboxMetricsBinder::oldestPendingAge)
                .description("Age of the oldest pending outbox event")
                .baseUnit("seconds")
                .register(registry);
    }

    private void bindStatus(MeterRegistry registry, String databaseStatus, String metricStatus) {
        Gauge.builder("digitalwallet.outbox.events", this, binder -> binder.count(databaseStatus))
                .description("Current outbox event count by actionable status")
                .baseUnit("events")
                .tag("status", metricStatus)
                .register(registry);
    }

    private double count(String status) {
        try {
            Long value = jdbc.queryForObject(COUNT_SQL, Long.class, status);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (DataAccessException failure) {
            return Double.NaN;
        }
    }

    private double oldestPendingAge() {
        try {
            Double value = jdbc.queryForObject(OLDEST_PENDING_SQL, Double.class);
            return value == null ? Double.NaN : value;
        } catch (DataAccessException failure) {
            return Double.NaN;
        }
    }
}
