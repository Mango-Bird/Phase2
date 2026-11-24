package sa.uet.digitalwallet_system.platform.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import sa.uet.digitalwallet_system.platform.security.JwtTokenService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Testcontainers
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
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
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
        String token = tokens.issue(1L, "monitor", "ROLE_USER").value();
        assertThat(get(managementPort, "/actuator/health/worker", null).statusCode()).isIn(401, 403);
        HttpResponse<String> worker = get(managementPort, "/actuator/health/worker", token);
        assertThat(worker.statusCode()).isEqualTo(503);
        assertThat(worker.body()).contains("OUT_OF_SERVICE").contains("DISABLED");
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus", token);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("digitalwallet_outbox_events")
                .contains("digitalwallet_outbox_worker_up")
                .containsPattern("digitalwallet_outbox_events\\{status=\"pending\"} 1\\.0");

        HttpResponse<String> applicationProbe = get(applicationPort, "/actuator/health/liveness", token);
        // The endpoint is absent on the application listener. The current C09-A-owned generic handler
        // maps Spring's no-resource exception to 500 rather than preserving 404, so assert non-exposure here.
        assertThat(applicationProbe.statusCode()).isNotEqualTo(200);
        assertThat(applicationProbe.body()).doesNotContain("livenessState");
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
