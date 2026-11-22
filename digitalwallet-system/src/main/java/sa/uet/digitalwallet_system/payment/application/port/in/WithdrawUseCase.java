package sa.uet.digitalwallet_system.payment.application.port.in;

import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

public interface WithdrawUseCase {
    Transaction withdraw(WithdrawCommand command);
}
