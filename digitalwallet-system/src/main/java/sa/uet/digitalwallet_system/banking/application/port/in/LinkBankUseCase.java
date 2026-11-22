package sa.uet.digitalwallet_system.banking.application.port.in;

import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;

public interface LinkBankUseCase {
    LinkedBankAccount link(LinkBankCommand command);
}
