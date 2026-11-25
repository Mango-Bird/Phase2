package sa.uet.digitalwallet_system.platform.observability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/** Owns a pool without publishing another DataSource bean that could replace the Payment datasource. */
@Component
final class OutboxMetricsDataSource {
    private final HikariDataSource pool;

    OutboxMetricsDataSource(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName,
            @Value("${app.observability.outbox.collection-deadline:1500ms}") Duration deadline) {
        long deadlineMillis = deadline.toMillis();
        if (deadlineMillis < 500 || deadlineMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("outbox metrics collection deadline must be between 500ms and Integer.MAX_VALUE ms");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("outbox-metrics-pool");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.setConnectionTimeout(deadlineMillis);
        config.setValidationTimeout(Math.max(250, Math.min(500, deadlineMillis / 2)));
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", Long.toString(deadlineMillis));
        config.addDataSourceProperty("socketTimeout", Long.toString(deadlineMillis));
        config.addDataSourceProperty("enableQueryTimeouts", "true");
        this.pool = new HikariDataSource(config);
    }

    Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    HikariDataSource pool() {
        return pool;
    }

    @PreDestroy
    void close() {
        pool.close();
    }
}
