package sa.uet.digitalwallet_system.payment.application.port.out;

import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

public interface SaveTransactionPort {
    Transaction save(Transaction transaction);
}
