package sa.uet.digitalwallet_system.payment.application.port.in;

import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

import java.util.List;

public interface GetTransactionsUseCase {
    List<Transaction> getTransactions(Long walletId);
}
