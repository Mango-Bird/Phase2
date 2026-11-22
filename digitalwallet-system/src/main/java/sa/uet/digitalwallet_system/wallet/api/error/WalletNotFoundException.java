package sa.uet.digitalwallet_system.wallet.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class WalletNotFoundException extends ModuleFailure {
    public WalletNotFoundException() { super(Kind.NOT_FOUND, "Wallet not found"); }
    public WalletNotFoundException(String message) { super(Kind.NOT_FOUND, message); }
}
