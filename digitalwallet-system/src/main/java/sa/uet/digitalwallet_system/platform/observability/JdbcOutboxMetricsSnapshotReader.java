package sa.uet.digitalwallet_system.platform.observability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
final class JdbcOutboxMetricsSnapshotReader implements OutboxMetricsSnapshotReader {
    static final String SNAPSHOT_SQL = """
            SELECT
                COALESCE(SUM(status = 'PENDING'), 0) AS pending_count,
                COALESCE(SUM(status = 'CLAIMED'), 0) AS claimed_count,
                COALESCE(SUM(status = 'DEAD'), 0) AS dead_count,
                COALESCE(GREATEST(
                    TIMESTAMPDIFF(MICROSECOND,
                        MIN(CASE WHEN status = 'PENDING' THEN created_at END), UTC_TIMESTAMP(6)) / 1000000.0,
                    0
                ), 0) AS oldest_pending_age_seconds
            FROM outbox_events
            WHERE status IN ('PENDING', 'CLAIMED', 'DEAD')
            """;

    private final OutboxMetricsDataSource dataSource;
    private final Duration deadline;
    private final Clock clock;
    private final String sql;

    @Autowired
    JdbcOutboxMetricsSnapshotReader(
            OutboxMetricsDataSource dataSource,
            @Value("${app.observability.outbox.collection-deadline:1500ms}") Duration deadline) {
        this(dataSource, deadline, Clock.systemUTC(), SNAPSHOT_SQL);
    }

    JdbcOutboxMetricsSnapshotReader(OutboxMetricsDataSource dataSource, Duration deadline, Clock clock, String sql) {
        if (deadline.isZero() || deadline.isNegative()) throw new IllegalArgumentException("collection deadline must be positive");
        this.dataSource = dataSource;
        this.deadline = deadline;
        this.clock = clock;
        this.sql = sql;
    }

    @Override
    public OutboxMetricsSnapshot read() {
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            int remainingMillis = remainingMillis(started);
            connection.setNetworkTimeout(Runnable::run, remainingMillis);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setMaxRows(1);
                statement.setQueryTimeout(Math.max(1, (int) Math.ceil(remainingMillis / 1000.0)));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("outbox metrics aggregate returned no row");
                    return new OutboxMetricsSnapshot(
                            result.getLong("pending_count"),
                            result.getLong("claimed_count"),
                            result.getLong("dead_count"),
                            result.getDouble("oldest_pending_age_seconds"),
                            clock.instant());
                }
            }
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("outbox metrics snapshot collection failed", failure);
        }
    }

    private int remainingMillis(long started) throws SQLTimeoutException {
        long elapsedNanos = System.nanoTime() - started;
        long remainingNanos = deadline.toNanos() - elapsedNanos;
        if (remainingNanos <= 0) throw new SQLTimeoutException("outbox metrics collection deadline expired while acquiring connection");
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }
}
