package sa.uet.digitalwallet_system.payment.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.application.port.in.TopupCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.TopupUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.TransferCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.TransferUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.WithdrawCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.WithdrawUseCase;
import sa.uet.digitalwallet_system.payment.application.port.out.SaveTransactionPort;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import sa.uet.digitalwallet_system.payment.application.PaymentEventPublisher;

import java.time.LocalDateTime;
import sa.uet.digitalwallet_system.wallet.api.*;
import sa.uet.digitalwallet_system.wallet.api.error.*;
import sa.uet.digitalwallet_system.banking.api.BankEligibilityApi;
import sa.uet.digitalwallet_system.payment.domain.MoneyRules;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService implements TopupUseCase, WithdrawUseCase, TransferUseCase {

    private final WalletPostingApi walletPosting;
    private final BankEligibilityApi bankAccounts;
    private final SaveTransactionPort saveTransactionPort;
    private final PaymentEventPublisher events;

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Transaction topup(TopupCommand command) {
        long amount = MoneyRules.positive(command.getAmount());
        bankAccounts.requireActiveForPosting(command.getLinkedBankAccountId(), command.getWalletId());
        PostingWallet wallet = walletPosting.loadOwnedForPosting(command.getActorUserId());
        if (wallet.id() != command.getWalletId()) throw new WalletNotFoundException();

        if (!"ACTIVE".equals(wallet.status())) {
            throw new WalletNotActiveException(wallet.walletNumber());
        }


        // Credit wallet
        walletPosting.applyBalanceChange(new BalanceChange(wallet.id(), wallet.version(), wallet.balance(), MoneyRules.credit(wallet.balance(), amount)));

        // Append ledger entry
        Transaction tx = Transaction.builder()
                .referenceCode(UUID.randomUUID().toString())
                .type("TOPUP")
                .sourceWalletId(null)
                .targetWalletId(wallet.id())
                .linkedBankAccountId(command.getLinkedBankAccountId())
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .build();

        Transaction saved = saveTransactionPort.save(tx);
        events.append(saved, null, wallet);
        return saved;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Transaction withdraw(WithdrawCommand command) {
        long amount = MoneyRules.positive(command.getAmount());
        bankAccounts.requireActiveForPosting(command.getLinkedBankAccountId(), command.getWalletId());
        PostingWallet wallet = walletPosting.loadOwnedForPosting(command.getActorUserId());
        if (wallet.id() != command.getWalletId()) throw new WalletNotFoundException();

        if (!"ACTIVE".equals(wallet.status())) {
            throw new WalletNotActiveException(wallet.walletNumber());
        }
        if (wallet.balance() < command.getAmount()) {
            throw new InsufficientBalanceException();
        }


        // Debit wallet
        walletPosting.applyBalanceChange(new BalanceChange(wallet.id(), wallet.version(), wallet.balance(), MoneyRules.debit(wallet.balance(), amount)));

        Transaction tx = Transaction.builder()
                .referenceCode(UUID.randomUUID().toString())
                .type("WITHDRAW")
                .sourceWalletId(wallet.id())
                .targetWalletId(null)
                .linkedBankAccountId(command.getLinkedBankAccountId())
                .amount(command.getAmount())
                .description(command.getDescription())
                .createdAt(LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .build();

        Transaction saved = saveTransactionPort.save(tx);
        events.append(saved, wallet, null);
        return saved;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Transaction transfer(TransferCommand command) {
        long amount = MoneyRules.positive(command.getAmount());
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

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        long debited = MoneyRules.debit(source.balance(), amount);
        long credited = MoneyRules.credit(target.balance(), amount);
        // Flush each owner write in ascending ID order; both roll back if either write or later stage fails.
        List.of(new BalanceChange(source.id(), source.version(), source.balance(), debited),
                        new BalanceChange(target.id(), target.version(), target.balance(), credited))
                .stream().sorted(java.util.Comparator.comparingLong(BalanceChange::walletId))
                .forEach(walletPosting::applyBalanceChange);

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

        Transaction saved = saveTransactionPort.save(tx);
        events.append(saved, source, target);
        return saved;
    }

}
