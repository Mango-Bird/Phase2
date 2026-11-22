package sa.uet.digitalwallet_system.payment.application.port.out;

import java.util.Optional;
import sa.uet.digitalwallet_system.payment.api.PaymentOperation;

public interface IdempotencyStore {
    void claim(long actorId, PaymentOperation operation, String key, String hash);
    void complete(long actorId, PaymentOperation operation, String key, long transactionId,
                  String referenceCode, String responseBody);
    Optional<StoredPayment> find(long actorId, PaymentOperation operation, String key);
    record StoredPayment(String hash, String status, int httpStatus, int responseVersion,
                         String responseBody, String referenceCode) {}
}
