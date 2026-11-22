package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import sa.uet.digitalwallet_system.payment.application.port.out.IdempotencyStore;

@Service
@RequiredArgsConstructor
public class PaymentReplayReader {
    private final IdempotencyStore store;
    @Transactional(readOnly = true)
    public PaymentReceipt replay(PaymentIntent intent, String key, String hash) {
        var saved = store.find(intent.actorId(), intent.operation(), key)
                .orElseThrow(() -> new PaymentFailure("REQUEST_IN_PROGRESS", "Request is in progress; retry with the same key", true));
        if (!"COMPLETED".equals(saved.status()))
            throw new PaymentFailure("REQUEST_IN_PROGRESS", "Request is in progress; retry with the same key", true);
        if (!saved.hash().equals(hash))
            throw new PaymentFailure("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for a different request", false);
        return new PaymentReceipt(saved.httpStatus(), saved.responseVersion(), saved.responseBody(), saved.referenceCode(), true);
    }
}
