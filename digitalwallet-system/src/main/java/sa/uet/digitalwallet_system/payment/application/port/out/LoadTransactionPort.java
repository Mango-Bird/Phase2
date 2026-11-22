package sa.uet.digitalwallet_system.payment.application.port.out;

import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

import java.util.List;

public interface LoadTransactionPort {
    List<Transaction> loadByWalletId(Long walletId);
}
