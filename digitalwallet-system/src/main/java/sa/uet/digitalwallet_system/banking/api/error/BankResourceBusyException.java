package sa.uet.digitalwallet_system.banking.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class BankResourceBusyException extends ModuleFailure {
    public BankResourceBusyException(Throwable cause) {
        super(Kind.INVALID_STATE, "RESOURCE_BUSY", "Bank link is busy; retry after backoff", true);
        initCause(cause);
    }
}
