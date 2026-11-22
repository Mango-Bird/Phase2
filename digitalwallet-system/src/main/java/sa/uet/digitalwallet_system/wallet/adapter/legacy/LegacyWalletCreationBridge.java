package sa.uet.digitalwallet_system.wallet.adapter.legacy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.application.port.out.SaveWalletPort;
import sa.uet.digitalwallet_system.domain.model.Wallet;
import sa.uet.digitalwallet_system.wallet.api.WalletCreationApi;
import sa.uet.digitalwallet_system.wallet.api.WalletView;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Transitional C03-B bridge. C03-A removes the two legacy dependencies below. */
@Component @RequiredArgsConstructor
public class LegacyWalletCreationBridge implements WalletCreationApi {
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
