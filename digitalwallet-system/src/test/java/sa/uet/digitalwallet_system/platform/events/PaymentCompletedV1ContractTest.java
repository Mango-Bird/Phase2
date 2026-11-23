package sa.uet.digitalwallet_system.platform.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;
import sa.uet.digitalwallet_system.platform.api.events.PaymentEventType;
import sa.uet.digitalwallet_system.platform.api.outbox.EventEnvelope;
import sa.uet.digitalwallet_system.platform.api.outbox.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void envelopeDeepCopiesNestedPayloadOnConstructionAndAccess() {
        ObjectNode original = mapper.createObjectNode();
        ObjectNode nested = original.putObject("nested");
        ArrayNode items = nested.putArray("items");
        items.addObject().put("value", "kept");
        EventEnvelope envelope = envelope(original);

        ((ObjectNode) items.get(0)).put("value", "mutated-original");
        nested.put("newField", true);
        assertThat(envelope.payload().at("/nested/items/0/value").asText()).isEqualTo("kept");
        assertThat(envelope.payload().at("/nested/newField").isMissingNode()).isTrue();

        ObjectNode returned = (ObjectNode) envelope.payload();
        ((ObjectNode) returned.at("/nested/items/0")).put("value", "mutated-accessor");
        ((ArrayNode) returned.at("/nested/items")).add("extra");
        assertThat(envelope.payload().at("/nested/items/0/value").asText()).isEqualTo("kept");
        assertThat(envelope.payload().at("/nested/items").size()).isEqualTo(1);
    }

    @Test
    void paymentAndReferenceUuidMustBeDistinctAndNonNull() {
        UUID same = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> event(same, same)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");

        PaymentCompletedV1 distinct = event(same,
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        assertThat(distinct.eventId()).isNotEqualTo(distinct.referenceCode());

        assertThatNullPointerException().isThrownBy(() -> event(null, distinct.referenceCode()))
                .withMessageContaining("eventId");
        assertThatNullPointerException().isThrownBy(() -> event(distinct.eventId(), null))
                .withMessageContaining("referenceCode");
    }

    private EventEnvelope envelope(JsonNode payload) {
        Instant created = Instant.parse("2026-11-17T00:00:00.123456Z");
        return new EventEnvelope(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "PAYMENT", 401L, PaymentCompletedV1.EVENT_TYPE, PaymentCompletedV1.SCHEMA_VERSION,
                payload, OutboxStatus.PENDING, 0, created, null, null, null, null, created, null);
    }

    private PaymentCompletedV1 event(UUID eventId, UUID referenceCode) {
        return PaymentCompletedV1.create(eventId, 401L, referenceCode, PaymentEventType.TRANSFER,
                100L, 10L, 1L, 20L, 2L, Instant.parse("2026-11-17T00:00:00Z"), "trace-401");
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
