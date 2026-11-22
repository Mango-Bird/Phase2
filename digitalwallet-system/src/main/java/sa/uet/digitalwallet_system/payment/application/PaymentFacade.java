package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentFacade implements PaymentCommandApi {
    private final WalletQueryApi wallets;
    private final PaymentExecutor executor;
    private final PaymentReplayReader reader;
    private final PaymentEncodingPort encoding;
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
        try {
            return executor.execute(intent, key, hash);
        } catch (ClaimConflict duplicate) {
            // Executor proxy has rolled back. Reader proxy starts a NEW transaction and sees committed result.
            return reader.replay(intent, key, hash);
        }
    }
}
