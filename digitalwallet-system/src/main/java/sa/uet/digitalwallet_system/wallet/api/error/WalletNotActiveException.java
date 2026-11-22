package sa.uet.digitalwallet_system.wallet.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class WalletNotActiveException extends ModuleFailure {
    public WalletNotActiveException(String walletNumber) {
        super(Kind.INVALID_STATE, "WALLET_NOT_ACTIVE", "Wallet is not active: " + walletNumber, false);
    }
}
