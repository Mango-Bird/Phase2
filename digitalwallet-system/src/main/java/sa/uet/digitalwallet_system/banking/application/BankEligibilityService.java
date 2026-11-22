package sa.uet.digitalwallet_system.banking.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.banking.api.*;
import sa.uet.digitalwallet_system.banking.api.error.*;
import sa.uet.digitalwallet_system.banking.application.port.out.LoadLinkedBankPort;

@Service
@RequiredArgsConstructor
public class BankEligibilityService implements BankEligibilityApi {
    private final LoadLinkedBankPort loadLinkedBankPort;
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public EligibleBank requireActiveForPosting(long linkedBankId, long walletId) {
        var account = loadLinkedBankPort.lockByIdAndWalletId(linkedBankId, walletId)
                .orElseThrow(LinkedBankNotFoundException::new);
        if (!"ACTIVE".equals(account.getStatus())) throw new BankNotActiveException();
        return new EligibleBank(account.getId(), account.getWalletId(), account.getBankCode());
    }
}
