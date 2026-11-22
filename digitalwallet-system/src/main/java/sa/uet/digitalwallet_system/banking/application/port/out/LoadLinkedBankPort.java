package sa.uet.digitalwallet_system.banking.application.port.out;

import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;

import java.util.List;
import java.util.Optional;

public interface LoadLinkedBankPort {
    List<LinkedBankAccount> loadActiveByWalletId(Long walletId);
    Optional<LinkedBankAccount> loadByIdAndWalletId(Long id, Long walletId);
    Optional<LinkedBankAccount> loadById(Long id);
}
