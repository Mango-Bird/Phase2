package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.port.in.*;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

@Service
@RequiredArgsConstructor
public class PaymentExecutor {
    private final IdempotencyStore store;
    private final TopupUseCase topups;
    private final WithdrawUseCase withdrawals;
    private final TransferUseCase transfers;
    private final PaymentEncodingPort encoding;
    private final PaymentFlushPort flush;

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PaymentReceipt execute(PaymentIntent intent, String key, String hash) {
        store.claim(intent.actorId(), intent.operation(), key, hash);
        Transaction tx = switch (intent.operation()) {
            case TOPUP -> topups.topup(new TopupCommand(intent.actorId(), intent.walletId(), intent.linkedBankId(), intent.amount(), intent.description()));
            case WITHDRAW -> withdrawals.withdraw(new WithdrawCommand(intent.actorId(), intent.walletId(), intent.linkedBankId(), intent.amount(), intent.description()));
            case TRANSFER -> transfers.transfer(new TransferCommand(intent.actorId(), intent.targetWalletNumber(), intent.amount(), intent.description()));
        };
        String body = encoding.successSnapshot(tx);
        flush.flush();
        store.complete(intent.actorId(), intent.operation(), key, tx.getId(), tx.getReferenceCode(), body);
        // MySQL JSON normalizes whitespace/key order. Return the stored representation on BOTH first and replay responses.
        var saved = store.find(intent.actorId(), intent.operation(), key).orElseThrow();
        return new PaymentReceipt(saved.httpStatus(), saved.responseVersion(), saved.responseBody(), saved.referenceCode(), false);
    }
}
