package sa.uet.digitalwallet_system.banking.application.port.out;

import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;

public interface SaveLinkedBankPort {
    LinkedBankAccount save(LinkedBankAccount linkedBankAccount);
}
