package sa.uet.digitalwallet_system.platform.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;
import sa.uet.digitalwallet_system.platform.api.events.PaymentEventType;
import sa.uet.digitalwallet_system.platform.api.outbox.EventEnvelope;
import sa.uet.digitalwallet_system.platform.api.outbox.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCompletedV1ContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void payloadV1SerializesWithStableContractAndDeserializesLosslessly() throws Exception {
        PaymentCompletedV1 event = transferEvent();
        String json = mapper.writeValueAsString(event);

        assertThat(json).isEqualTo("""
                {"eventId":"00000000-0000-0000-0000-000000000001","eventType":"payment.completed.v1","schemaVersion":1,"transactionId":401,"referenceCode":"00000000-0000-0000-0000-000000000401","type":"TRANSFER","amount":100,"currency":"VND","sourceWalletId":10,"sourceUserId":1,"targetWalletId":20,"targetUserId":2,"occurredAt":"2026-11-17T00:00:00Z","correlationId":"trace-401"}
                """.strip());
        assertThat(mapper.readValue(json, PaymentCompletedV1.class)).isEqualTo(event);
    }

    @Test
    void storageEnvelopeRoundTripsWithoutChangingImmutablePayload() throws Exception {
        PaymentCompletedV1 event = transferEvent();
        JsonNode payload = mapper.valueToTree(event);
        Instant created = Instant.parse("2026-11-17T00:00:00.123456Z");
        EventEnvelope envelope = new EventEnvelope(event.eventId(), "PAYMENT", event.transactionId(),
                event.eventType(), event.schemaVersion(), payload, OutboxStatus.PENDING, 0,
                created, null, null, null, null, created, null);

        EventEnvelope decoded = mapper.readValue(mapper.writeValueAsBytes(envelope), EventEnvelope.class);
        assertThat(decoded.eventId()).isEqualTo(envelope.eventId());
        assertThat(decoded.aggregateType()).isEqualTo("PAYMENT");
        assertThat(decoded.aggregateId()).isEqualTo(401L);
        assertThat(decoded.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(decoded.nextAttemptAt()).isEqualTo(created);
        assertThat(decoded.createdAt()).isEqualTo(created);
        assertThat(mapper.treeToValue(decoded.payload(), PaymentCompletedV1.class)).isEqualTo(event);
    }

    private PaymentCompletedV1 transferEvent() {
        return PaymentCompletedV1.create(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                401L,
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                PaymentEventType.TRANSFER,
                100L,
                10L, 1L, 20L, 2L,
                Instant.parse("2026-11-17T00:00:00Z"),
                "trace-401");
    }
}
