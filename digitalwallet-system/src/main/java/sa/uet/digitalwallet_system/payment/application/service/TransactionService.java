package sa.uet.digitalwallet_system.payment.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.application.port.in.GetTransactionsUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.TopupCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.TopupUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.TransferCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.TransferUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.WithdrawCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.WithdrawUseCase;
import sa.uet.digitalwallet_system.payment.application.port.out.LoadTransactionPort;
import sa.uet.digitalwallet_system.payment.application.port.out.SaveTransactionPort;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

import java.time.LocalDateTime;
import sa.uet.digitalwallet_system.wallet.api.*;
import sa.uet.digitalwallet_system.wallet.api.error.*;
import sa.uet.digitalwallet_system.banking.api.BankAccountQueryApi;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService implements TopupUseCase, WithdrawUseCase, TransferUseCase, GetTransactionsUseCase {

    private final WalletPostingApi walletPosting;
    private final BankAccountQueryApi bankAccounts;
    private final SaveTransactionPort saveTransactionPort;
    private final LoadTransactionPort loadTransactionPort;

    @Override
    @Transactional
    public Transaction topup(TopupCommand command) {
        PostingWallet wallet = walletPosting.loadOwnedForPosting(command.getActorUserId());

        if (!"ACTIVE".equals(wallet.status())) {
            throw new WalletNotActiveException(wallet.walletNumber());
        }

        // Verify bank account belongs to user's wallet
        bankAccounts.requireActiveAccount(command.getLinkedBankAccountId(), wallet.id());

        // Credit wallet
        walletPosting.applyBalanceChange(new BalanceChange(wallet.id(), wallet.version(), wallet.balance(), wallet.balance() + command.getAmount()));

        // Append ledger entry
        Transaction tx = Transaction.builder()
                .referenceCode(UUID.randomUUID().toString())
                .type("TOPUP")
                .sourceWalletId(null)
                .targetWalletId(wallet.id())
                .linkedBankAccountId(command.getLinkedBankAccountId())
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(LocalDateTime.now(java.time.ZoneOffset.UTC))
                .build();

        return saveTransactionPort.save(tx);
    }

    @Override
    @Transactional
    public Transaction withdraw(WithdrawCommand command) {
        PostingWallet wallet = walletPosting.loadOwnedForPosting(command.getActorUserId());

        if (!"ACTIVE".equals(wallet.status())) {
            throw new WalletNotActiveException(wallet.walletNumber());
        }
        if (wallet.balance() < command.getAmount()) {
            throw new InsufficientBalanceException();
        }

        // Verify bank account belongs to user's wallet
        bankAccounts.requireActiveAccount(command.getLinkedBankAccountId(), wallet.id());

        // Debit wallet
        walletPosting.applyBalanceChange(new BalanceChange(wallet.id(), wallet.version(), wallet.balance(), wallet.balance() - command.getAmount()));

        Transaction tx = Transaction.builder()
                .referenceCode(UUID.randomUUID().toString())
                .type("WITHDRAW")
                .sourceWalletId(wallet.id())
                .targetWalletId(null)
                .linkedBankAccountId(command.getLinkedBankAccountId())
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(LocalDateTime.now(java.time.ZoneOffset.UTC))
                .build();

        return saveTransactionPort.save(tx);
    }

    @Override
    @Transactional
    public Transaction transfer(TransferCommand command) {
        PostingWallet source = walletPosting.loadOwnedForPosting(command.getActorUserId());
        PostingWallet target = walletPosting.loadTargetForPosting(command.getTargetWalletNumber());

        if (source.id() == target.id()) {
            throw new sa.uet.digitalwallet_system.payment.api.error.PaymentFailure("SAME_WALLET", "Cannot transfer to the same wallet", false);
        }
        if (!"ACTIVE".equals(source.status())) {
            throw new WalletNotActiveException(source.walletNumber());
        }
        if (!"ACTIVE".equals(target.status())) {
            throw new WalletNotActiveException(target.walletNumber());
        }
        if (source.balance() < command.getAmount()) {
            throw new InsufficientBalanceException();
        }

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        // Atomic debit source, credit target
        walletPosting.applyBalanceChange(new BalanceChange(source.id(), source.version(), source.balance(), source.balance() - command.getAmount()));
        walletPosting.applyBalanceChange(new BalanceChange(target.id(), target.version(), target.balance(), target.balance() + command.getAmount()));

        Transaction tx = Transaction.builder()
                .referenceCode(UUID.randomUUID().toString())
                .type("TRANSFER")
                .sourceWalletId(source.id())
                .targetWalletId(target.id())
                .linkedBankAccountId(null)
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(now)
                .build();

        return saveTransactionPort.save(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Transaction> getTransactions(Long walletId) {
        return loadTransactionPort.loadByWalletId(walletId);
    }
}
