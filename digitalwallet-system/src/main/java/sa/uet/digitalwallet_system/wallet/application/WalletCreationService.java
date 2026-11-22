package sa.uet.digitalwallet_system.wallet.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.wallet.application.port.out.SaveWalletPort;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;
import sa.uet.digitalwallet_system.wallet.api.WalletCreationApi;
import sa.uet.digitalwallet_system.wallet.api.WalletView;

import java.time.LocalDateTime;
import sa.uet.digitalwallet_system.wallet.application.port.out.WalletNumberGenerator;
import java.time.ZoneOffset;

/** Native wallet creation joins the atomic onboarding transaction. */
@Component @RequiredArgsConstructor
public class WalletCreationService implements WalletCreationApi {
    private final SaveWalletPort saveWalletPort;
    private final WalletNumberGenerator walletNumberGenerator;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public WalletView createInitialWallet(long userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Wallet saved = saveWalletPort.save(Wallet.builder().userId(userId).walletNumber(walletNumberGenerator.next())
                .balance(0L).status("ACTIVE").version(0L).createdAt(now).updatedAt(now).build());
        return new WalletView(saved.getId(), saved.getUserId(), saved.getWalletNumber(), saved.getBalance(),
                saved.getStatus(), saved.getVersion(), saved.getCreatedAt().toInstant(ZoneOffset.UTC),
                saved.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
