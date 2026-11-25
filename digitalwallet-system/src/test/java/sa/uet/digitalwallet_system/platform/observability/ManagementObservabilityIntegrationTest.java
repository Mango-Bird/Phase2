package sa.uet.digitalwallet_system.platform.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import sa.uet.digitalwallet_system.platform.security.JwtTokenService;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManagementObservabilityIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("c09_observability").withUsername("test_user").withPassword("test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort int applicationPort;
    @LocalManagementPort int managementPort;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxMetricsCollector collector;
    @Autowired OutboxMetricsDataSource metricsDataSource;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void stopBackgroundCollectionAndResetOutbox() {
        collector.stop();
        jdbc.update("DELETE FROM outbox_events");
        assertThat(collector.collectNow()).isTrue();
    }

    @Test
    @Order(1)
    void probesArePrivateListenerOnlyAndMetricsRequireCurrentAuthentication() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/prometheus", null).statusCode()).isIn(401, 403);

        jdbc.update("""
                INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, event_type,
                    schema_version, payload, status, attempts, next_attempt_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000000901', 'PAYMENT', 901,
                    'payment.completed.v1', 1, JSON_OBJECT(), 'PENDING', 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) - INTERVAL 5 SECOND)
                """);
        jdbc.update("""
                INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, event_type,
                    schema_version, payload, status, attempts, next_attempt_at, lease_until,
                    claim_token, claim_owner, created_at)
                VALUES ('00000000-0000-0000-0000-000000000911', 'PAYMENT', 911,
                    'payment.completed.v1', 1, JSON_OBJECT(), 'CLAIMED', 1,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 30 SECOND,
                    '00000000-0000-0000-0000-000000000912', 'test-worker', UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, event_type,
                    schema_version, payload, status, attempts, next_attempt_at, last_error,
                    created_at, processed_at)
                VALUES ('00000000-0000-0000-0000-000000000913', 'PAYMENT', 913,
                    'payment.completed.v1', 1, JSON_OBJECT(), 'DEAD', 5,
                    UTC_TIMESTAMP(6), 'test-terminal-error', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        assertThat(collector.collectNow()).isTrue();
        assertThat(collector.lastSuccessfulSnapshot())
                .extracting(OutboxMetricsSnapshot::pending, OutboxMetricsSnapshot::claimed,
                        OutboxMetricsSnapshot::dead)
                .containsExactly(1L, 1L, 1L);
        assertThat(collector.lastSuccessfulSnapshot().oldestPendingAgeSeconds()).isGreaterThanOrEqualTo(5.0);
        String token = tokens.issue(1L, "monitor", "ROLE_USER").value();
        assertThat(get(managementPort, "/actuator/health/worker", null).statusCode()).isIn(401, 403);
        HttpResponse<String> worker = get(managementPort, "/actuator/health/worker", token);
        assertThat(worker.statusCode()).isEqualTo(503);
        assertThat(worker.body()).contains("OUT_OF_SERVICE").contains("DISABLED");
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus", token);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("digitalwallet_outbox_events")
                .contains("digitalwallet_outbox_worker_up")
                .containsPattern("digitalwallet_outbox_events\\{status=\"pending\"} 1\\.0")
                .containsPattern("digitalwallet_outbox_events\\{status=\"claimed\"} 1\\.0")
                .containsPattern("digitalwallet_outbox_events\\{status=\"dead\"} 1\\.0");

        HttpResponse<String> applicationProbe = get(applicationPort, "/actuator/health/liveness", token);
        // The endpoint is absent on the application listener. The current C09-A-owned generic handler
        // maps Spring's no-resource exception to 500 rather than preserving 404, so assert non-exposure here.
        assertThat(applicationProbe.statusCode()).isNotEqualTo(200);
        assertThat(applicationProbe.body()).doesNotContain("livenessState");
    }

    @Test
    @Order(2)
    void slowDatabaseQueryTimesOutWithoutBlockingRealPrometheusScrapeAndThenRecovers() throws Exception {
        String slowSql = """
                SELECT 1 AS pending_count, 2 AS claimed_count, 3 AS dead_count,
                       SLEEP(5) AS oldest_pending_age_seconds
                """;
        JdbcOutboxMetricsSnapshotReader slowReader = new JdbcOutboxMetricsSnapshotReader(
                metricsDataSource, Duration.ofMillis(700), Clock.systemUTC(), slowSql);
        String token = monitoringToken();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var blockedCollection = executor.submit(slowReader::read);
            awaitMetricConnectionInUse();
            long started = System.nanoTime();
            HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", token);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(elapsedMillis).isLessThan(1000);
            assertSystemMetricsSurvive(scrape.body());
            assertThatThrownByFuture(blockedCollection);
        }

        jdbc.update("""
                INSERT INTO outbox_events(event_id, aggregate_type, aggregate_id, event_type,
                    schema_version, payload, status, attempts, next_attempt_at, created_at)
                VALUES ('00000000-0000-0000-0000-000000000902', 'PAYMENT', 902,
                    'payment.completed.v1', 1, JSON_OBJECT(), 'PENDING', 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        awaitSuccessfulCollection();
        assertThat(get(managementPort, "/actuator/prometheus", token).body())
                .containsPattern("digitalwallet_outbox_events\\{status=\"pending\"} 1\\.0");
    }

    @Test
    @Order(3)
    void exhaustedMetricsPoolCannotDelayScrapeOrStarvePaymentPool() throws Exception {
        String token = monitoringToken();
        try (Connection held = metricsDataSource.getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            var timedCollection = executor.submit(collector::collectNow);
            awaitMetricsConnectionWaiter();
            long started = System.nanoTime();
            HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", token);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(elapsedMillis).isLessThan(1000);
            assertSystemMetricsSurvive(scrape.body());
            assertThat(timedCollection.get(3, TimeUnit.SECONDS)).isFalse();
            assertThat(metricsDataSource.pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        }

        assertThat(collector.collectNow()).isTrue();
        assertThat(metricsDataSource.pool().getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(metricsDataSource.pool().getHikariPoolMXBean().getTotalConnections()).isLessThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void unavailableMetricsDatasourceDoesNotBlockScrapeOrHideSystemMetrics() throws Exception {
        String token = monitoringToken();
        metricsDataSource.close();
        assertThat(collector.collectNow()).isFalse();

        long started = System.nanoTime();
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", token);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(elapsedMillis).isLessThan(1000);
        assertSystemMetricsSurvive(scrape.body());
        assertThat(scrape.body()).contains("digitalwallet_outbox_events");
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String monitoringToken() {
        return tokens.issue(1L, "monitor", "ROLE_USER").value();
    }

    private void awaitMetricConnectionInUse() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (metricsDataSource.pool().getHikariPoolMXBean().getActiveConnections() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(metricsDataSource.pool().getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
    }

    private void awaitMetricsConnectionWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (metricsDataSource.pool().getHikariPoolMXBean().getThreadsAwaitingConnection() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(metricsDataSource.pool().getHikariPoolMXBean().getThreadsAwaitingConnection()).isEqualTo(1);
    }

    private void awaitSuccessfulCollection() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean collected = false;
        while (!collected && System.nanoTime() < deadline) {
            collected = collector.collectNow();
            if (!collected) {
                Thread.sleep(25);
            }
        }
        assertThat(collected).as("metrics collection recovers before its bounded test deadline").isTrue();
    }

    private static void assertSystemMetricsSurvive(String body) {
        assertThat(body).contains("http_server_requests_seconds")
                .contains("hikaricp_connections_active");
    }

    private static void assertThatThrownByFuture(java.util.concurrent.Future<?> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            throw new AssertionError("slow query unexpectedly completed");
        } catch (ExecutionException expected) {
            assertThat(expected.getCause()).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        }
    }
}
