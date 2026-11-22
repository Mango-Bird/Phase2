package sa.uet.digitalwallet_system.wallet.application;

import sa.uet.digitalwallet_system.wallet.api.WalletView;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;
import java.time.ZoneOffset;

public final class WalletViews {
    private WalletViews() {}
    public static WalletView fromDomain(Wallet w) {
        return new WalletView(w.getId(), w.getUserId(), w.getWalletNumber(), w.getBalance(), w.getStatus(),
                w.getVersion(), w.getCreatedAt().toInstant(ZoneOffset.UTC), w.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
