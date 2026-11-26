package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;
import sa.uet.digitalwallet_system.platform.api.events.PaymentEventType;
import sa.uet.digitalwallet_system.platform.api.observability.CorrelationIdProvider;
import sa.uet.digitalwallet_system.platform.api.outbox.OutboxAppendPort;
import sa.uet.digitalwallet_system.wallet.api.PostingWallet;

import java.time.ZoneOffset;
import java.util.UUID;

/** Called only on the newly executed posting path, using its verified wallet snapshots. */
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {
    private final OutboxAppendPort outbox;
    private final CorrelationIdProvider correlationIds;

    public void append(Transaction transaction, PostingWallet source, PostingWallet target) {
        outbox.append(PaymentCompletedV1.create(UUID.randomUUID(), transaction.getId(),
                UUID.fromString(transaction.getReferenceCode()), PaymentEventType.valueOf(transaction.getType()),
                transaction.getAmount(), source == null ? null : source.id(), source == null ? null : source.userId(),
                target == null ? null : target.id(), target == null ? null : target.userId(),
                transaction.getCreatedAt().toInstant(ZoneOffset.UTC), correlationIds.currentOrCreate()));
    }
}
