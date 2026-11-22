package sa.uet.digitalwallet_system.wallet.application.port.out;

import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;

public interface SaveWalletPort {
    Wallet save(Wallet wallet);
}
