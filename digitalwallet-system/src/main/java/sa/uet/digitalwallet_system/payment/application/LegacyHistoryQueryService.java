package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.application.port.in.GetTransactionsUseCase;
import sa.uet.digitalwallet_system.payment.application.port.out.LoadTransactionPort;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.util.List;

/** v1 compatibility only: deliberately retains the original unbounded list. */
@Service @RequiredArgsConstructor
public class LegacyHistoryQueryService implements GetTransactionsUseCase {
    private final LoadTransactionPort history;
    @Override @Transactional(readOnly=true)
    public List<Transaction> getTransactions(Long walletId) { return history.loadByWalletId(walletId); }
}
