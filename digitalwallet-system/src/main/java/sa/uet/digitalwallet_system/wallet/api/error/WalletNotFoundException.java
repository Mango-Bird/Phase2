package sa.uet.digitalwallet_system.wallet.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class WalletNotFoundException extends ModuleFailure {
    public WalletNotFoundException() { super(Kind.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found", false); }
    public WalletNotFoundException(String message) { super(Kind.NOT_FOUND, "WALLET_NOT_FOUND", message, false); }
}
