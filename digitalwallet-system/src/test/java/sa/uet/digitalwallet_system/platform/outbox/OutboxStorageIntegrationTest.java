package sa.uet.digitalwallet_system.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;
import sa.uet.digitalwallet_system.platform.api.events.PaymentEventType;
import sa.uet.digitalwallet_system.platform.api.outbox.OutboxAppendPort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest
@Import(OutboxStorageIntegrationTest.ProbeConfiguration.class)
class OutboxStorageIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("c06_outbox").withUsername("test_user").withPassword("test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired OutboxAppendPort outbox;
    @Autowired TransactionProbe transactions;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void appendCommitsInsideCallerTransactionWithExactPayloadAndPendingEnvelope() throws Exception {
        PaymentCompletedV1 event = event(1, 401);
        transactions.commit(event);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(1L);
        var row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE event_id=?", event.eventId().toString());
        assertThat(row).containsEntry("aggregate_type", "PAYMENT")
                .containsEntry("aggregate_id", 401L)
                .containsEntry("event_type", PaymentCompletedV1.EVENT_TYPE)
                .containsEntry("schema_version", 1)
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0);
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("claim_owner")).isNull();
        PaymentCompletedV1 decoded = mapper.readValue((String) row.get("payload"), PaymentCompletedV1.class);
        assertThat(decoded).isEqualTo(event);
    }

    @Test
    void appendOutsideCallerTransactionIsRejectedBeforeInsert() {
        assertThatThrownBy(() -> outbox.append(event(2, 402)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
    }

    @Test
    void callerRollbackAfterAppendLeavesNoOrphanEvent() {
        assertThatThrownBy(() -> transactions.appendThenFail(event(3, 403)))
                .isInstanceOf(InjectedFault.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
    }

    @Test
    void aggregateAndEventTypeUniquenessRejectsDuplicateBusinessEvent() {
        transactions.commit(event(4, 404));
        assertThatThrownBy(() -> transactions.commit(event(5, 404)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_outbox_aggregate_event");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(1L);
    }

    private PaymentCompletedV1 event(long eventSuffix, long transactionId) {
        return PaymentCompletedV1.create(
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(eventSuffix)),
                transactionId,
                UUID.fromString("00000000-0000-0000-0001-%012d".formatted(transactionId)),
                PaymentEventType.TRANSFER,
                100L,
                10L, 1L, 20L, 2L,
                Instant.parse("2026-11-17T00:00:00Z"),
                "trace-" + transactionId);
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean TransactionProbe transactionProbe(OutboxAppendPort outbox) { return new TransactionProbe(outbox); }
    }

    static class TransactionProbe {
        private final OutboxAppendPort outbox;
        TransactionProbe(OutboxAppendPort outbox) { this.outbox = outbox; }

        @Transactional
        public void commit(PaymentCompletedV1 event) { outbox.append(event); }

        @Transactional
        public void appendThenFail(PaymentCompletedV1 event) {
            outbox.append(event);
            throw new InjectedFault();
        }
    }

    static class InjectedFault extends RuntimeException {
    }
}
