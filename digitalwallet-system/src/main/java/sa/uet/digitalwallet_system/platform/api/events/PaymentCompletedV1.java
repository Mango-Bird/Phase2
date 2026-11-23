package sa.uet.digitalwallet_system.platform.api.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentCompletedV1(
        UUID eventId,
        String eventType,
        int schemaVersion,
        long transactionId,
        UUID referenceCode,
        PaymentEventType type,
        long amount,
        String currency,
        Long sourceWalletId,
        Long sourceUserId,
        Long targetWalletId,
        Long targetUserId,
        Instant occurredAt,
        String correlationId) {

    public static final String EVENT_TYPE = "payment.completed.v1";
    public static final int SCHEMA_VERSION = 1;

    public PaymentCompletedV1 {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(referenceCode, "referenceCode");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventId.equals(referenceCode)) throw new IllegalArgumentException("eventId and referenceCode must differ");
        if (!EVENT_TYPE.equals(eventType)) throw new IllegalArgumentException("eventType must be " + EVENT_TYPE);
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("schemaVersion must be 1");
        if (transactionId <= 0) throw new IllegalArgumentException("transactionId must be positive");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (!"VND".equals(currency)) throw new IllegalArgumentException("currency must be VND");
        if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId is required");
        validateParticipants(type, sourceWalletId, sourceUserId, targetWalletId, targetUserId);
    }

    public static PaymentCompletedV1 create(UUID eventId, long transactionId, UUID referenceCode,
                                             PaymentEventType type, long amount,
                                             Long sourceWalletId, Long sourceUserId,
                                             Long targetWalletId, Long targetUserId,
                                             Instant occurredAt, String correlationId) {
        return new PaymentCompletedV1(eventId, EVENT_TYPE, SCHEMA_VERSION, transactionId, referenceCode,
                type, amount, "VND", sourceWalletId, sourceUserId, targetWalletId, targetUserId,
                occurredAt, correlationId);
    }

    private static void validateParticipants(PaymentEventType type, Long sourceWalletId, Long sourceUserId,
                                             Long targetWalletId, Long targetUserId) {
        boolean sourcePresent = sourceWalletId != null && sourceUserId != null;
        boolean targetPresent = targetWalletId != null && targetUserId != null;
        boolean valid = switch (type) {
            case TOPUP -> !sourcePresent && sourceWalletId == null && sourceUserId == null && targetPresent;
            case WITHDRAW -> sourcePresent && !targetPresent && targetWalletId == null && targetUserId == null;
            case TRANSFER -> sourcePresent && targetPresent;
        };
        if (!valid) throw new IllegalArgumentException("wallet/user participants do not match payment type");
    }
}
