package sa.uet.digitalwallet_system.platform.adapter.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;
import sa.uet.digitalwallet_system.platform.api.outbox.OutboxAppendPort;

@Component
public class JdbcOutboxAppendAdapter implements OutboxAppendPort {
    private static final String INSERT_SQL = """
            INSERT INTO outbox_events (
                event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                status, attempts, next_attempt_at, created_at
            ) VALUES (?, 'PAYMENT', ?, ?, ?, CAST(? AS JSON), 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JdbcOutboxAppendAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PaymentCompletedV1 event) {
        jdbcTemplate.update(INSERT_SQL, event.eventId().toString(), event.transactionId(), event.eventType(),
                event.schemaVersion(), serialize(event));
    }

    private String serialize(PaymentCompletedV1 event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payment event cannot be serialized", exception);
        }
    }
}
