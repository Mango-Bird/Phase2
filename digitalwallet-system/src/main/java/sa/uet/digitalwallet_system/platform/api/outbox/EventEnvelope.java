package sa.uet.digitalwallet_system.platform.api.outbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(UUID eventId, String aggregateType, long aggregateId, String eventType,
                            int schemaVersion, JsonNode payload, OutboxStatus status, int attempts,
                            Instant nextAttemptAt, Instant leaseUntil, UUID claimToken, String claimOwner,
                            String lastError, Instant createdAt, Instant processedAt) {
    public EventEnvelope {
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
