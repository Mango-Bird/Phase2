package sa.uet.digitalwallet_system.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import sa.uet.digitalwallet_system.payment.api.PaymentOperation;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Only WebMvc slices: JDBC atomicity is tested separately against MySQL, not simulated here. */
@org.springframework.context.annotation.Import(sa.uet.digitalwallet_system.payment.application.LegacyHistoryQueryService.class)
@TestConfiguration
public class PaymentMvcStorageConfiguration {
    // MVC slices verify HTTP contracts only; real transactional append is covered by MySQL suites.
    @Bean sa.uet.digitalwallet_system.payment.application.PaymentEventPublisher events() {
        return new sa.uet.digitalwallet_system.payment.application.PaymentEventPublisher(event -> {},
                new sa.uet.digitalwallet_system.platform.observability.MdcCorrelationIdProvider());
    }
    @Bean PaymentRetryDelayPort retryDelay() { return attempt -> {}; }
    @Bean PaymentFlushPort flush() { return () -> {}; }
    @Bean IdempotencyStore store() {
        return new IdempotencyStore() {
            final ConcurrentHashMap<String, StoredPayment> rows = new ConcurrentHashMap<>();
            String id(long actor, PaymentOperation op, String key) { return actor + ":" + op + ":" + key; }
            public void claim(long actor, PaymentOperation op, String key, String hash) {
                if (rows.putIfAbsent(id(actor, op, key), new StoredPayment(hash, "PROCESSING", 0, 0, null, null)) != null)
                    throw new ClaimConflict(new IllegalStateException("duplicate test key"));
            }
            public void complete(long actor, PaymentOperation op, String key, long tx, String reference, String body) {
                rows.compute(id(actor, op, key), (ignored, row) -> new StoredPayment(row.hash(), "COMPLETED", 200, 1, body, reference));
            }
            public Optional<StoredPayment> find(long actor, PaymentOperation op, String key) { return Optional.ofNullable(rows.get(id(actor, op, key))); }
        };
    }
    public static Transaction saved(Transaction tx) {
        return Transaction.builder().id(101L).referenceCode(tx.getReferenceCode()).type(tx.getType())
                .sourceWalletId(tx.getSourceWalletId()).targetWalletId(tx.getTargetWalletId())
                .linkedBankAccountId(tx.getLinkedBankAccountId()).amount(tx.getAmount())
                .description(tx.getDescription()).createdAt(tx.getCreatedAt()).build();
    }
}
