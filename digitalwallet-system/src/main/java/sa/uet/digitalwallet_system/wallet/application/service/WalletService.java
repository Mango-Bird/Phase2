package sa.uet.digitalwallet_system.wallet.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;
import sa.uet.digitalwallet_system.wallet.api.WalletView;
import sa.uet.digitalwallet_system.wallet.api.error.WalletNotFoundException;
import sa.uet.digitalwallet_system.wallet.application.WalletViews;
import sa.uet.digitalwallet_system.wallet.application.port.out.LoadWalletPort;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService implements WalletQueryApi {
    private final LoadWalletPort loadWalletPort;

    @Override
    public WalletView getOwnedWallet(long actorUserId) {
        return WalletViews.fromDomain(loadWalletPort.loadByUserId(actorUserId)
                .orElseThrow(WalletNotFoundException::new));
    }

    @Override
    public WalletView getOwnedWalletById(long actorUserId, long walletId) {
        WalletView wallet = getOwnedWallet(actorUserId);
        if (wallet.id() != walletId) throw new WalletNotFoundException();
        return wallet;
    }
}
