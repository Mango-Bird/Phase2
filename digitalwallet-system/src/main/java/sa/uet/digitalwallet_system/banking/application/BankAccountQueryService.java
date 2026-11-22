package sa.uet.digitalwallet_system.banking.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.banking.api.BankAccountQueryApi;
import sa.uet.digitalwallet_system.banking.api.error.LinkedBankNotFoundException;
import sa.uet.digitalwallet_system.banking.application.port.out.LoadLinkedBankPort;

@Service
@RequiredArgsConstructor
public class BankAccountQueryService implements BankAccountQueryApi {
    private final LoadLinkedBankPort loadLinkedBankPort;
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireActiveAccount(long linkedBankId, long walletId) {
        var account = loadLinkedBankPort.loadByIdAndWalletId(linkedBankId, walletId)
                .orElseThrow(LinkedBankNotFoundException::new);
        if (!"ACTIVE".equals(account.getStatus())) throw new IllegalArgumentException("Linked bank account is not active");
    }
}
