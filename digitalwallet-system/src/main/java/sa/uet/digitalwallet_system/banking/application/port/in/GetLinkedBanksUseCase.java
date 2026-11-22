package sa.uet.digitalwallet_system.banking.application.port.in;

import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;

import java.util.List;

public interface GetLinkedBanksUseCase {
    List<LinkedBankAccount> getLinkedBanks(Long walletId);
}
