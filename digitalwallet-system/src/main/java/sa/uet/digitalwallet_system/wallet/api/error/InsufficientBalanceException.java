package sa.uet.digitalwallet_system.wallet.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class InsufficientBalanceException extends ModuleFailure {
    public InsufficientBalanceException() { super(Kind.INSUFFICIENT_BALANCE, "Insufficient balance"); }
}
