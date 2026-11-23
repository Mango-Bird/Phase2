package sa.uet.digitalwallet_system.platform.api.outbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(UUID eventId, String aggregateType, long aggregateId, String eventType,
                            int schemaVersion, JsonNode payload, OutboxStatus status, int attempts,
                            Instant nextAttemptAt, Instant leaseUntil, UUID claimToken, String claimOwner,
                            String lastError, Instant createdAt, Instant processedAt) {
}
