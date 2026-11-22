package sa.uet.digitalwallet_system.banking.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class LinkedBankNotFoundException extends ModuleFailure {
    public LinkedBankNotFoundException() { super(Kind.NOT_FOUND, "Linked bank account not found"); }
}
