package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;
import sa.uet.digitalwallet_system.platform.api.error.SqlFailures;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentFacade implements PaymentCommandApi {
    private final WalletQueryApi wallets;
    private final PaymentExecutor executor;
    private final PaymentReplayReader reader;
    private final PaymentEncodingPort encoding;
    private final PaymentRetryDelayPort retryDelay;
    public PaymentReceipt topup(long actor, String key, TopupInput input) {
        return submit(actor, key, PaymentOperation.TOPUP, input.linkedBankAccountId(), null, input.amount(), input.description());
    }
    public PaymentReceipt withdraw(long actor, String key, WithdrawInput input) {
        return submit(actor, key, PaymentOperation.WITHDRAW, input.linkedBankAccountId(), null, input.amount(), input.description());
    }
    public PaymentReceipt transfer(long actor, String key, TransferInput input) {
        PaymentValidation.target(input.targetWalletNumber());
        return submit(actor, key, PaymentOperation.TRANSFER, null, input.targetWalletNumber(), input.amount(), input.description());
    }
    private PaymentReceipt submit(long actor, String key, PaymentOperation operation, Long bank, String target, long amount, String description) {
        PaymentValidation.key(key);
        PaymentValidation.payload(amount, description);
        long wallet = wallets.getOwnedWallet(actor).id(); // Inactive is allowed: replay must bypass current business state.
        var intent = new PaymentIntent(actor, operation, wallet, bank, target, amount, description);
        String hash = encoding.requestHash(intent);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return executor.execute(intent, key, hash);
            } catch (ClaimConflict duplicate) {
                // Executor proxy rolled back. Replay starts a new transaction, never a cached persistence context.
                return reader.replay(intent, key, hash);
            } catch (RuntimeException failure) {
                if (!PaymentRetryClassifier.retryable(failure)) {
                    if (!(failure instanceof sa.uet.digitalwallet_system.platform.api.error.ModuleFailure) && SqlFailures.isMySqlLockTimeout(failure))
                        throw new PaymentFailure("RESOURCE_BUSY", "Resource is busy; retry with the same key", true);
                    throw failure;
                }
                if (attempt == 3) throw new PaymentFailure("CONCURRENT_UPDATE", "Concurrent update; retry with the same key", true);
                // No transaction/claim survives while sleeping. The next proxy invocation creates a fresh transaction.
                retryDelay.pauseBeforeRetry(attempt + 1);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }
}
