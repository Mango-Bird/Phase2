package sa.uet.digitalwallet_system.banking.api.error;
import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;
public class BankNotActiveException extends ModuleFailure {
    public BankNotActiveException() { super(Kind.INVALID_STATE, "BANK_NOT_ACTIVE", "Linked bank account is not active", false); }
}
