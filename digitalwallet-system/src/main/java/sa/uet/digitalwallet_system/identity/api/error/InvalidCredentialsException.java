package sa.uet.digitalwallet_system.identity.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class InvalidCredentialsException extends ModuleFailure {
    public InvalidCredentialsException() {
        super(Kind.INVALID_CREDENTIALS, "Invalid username or password");
    }
}
