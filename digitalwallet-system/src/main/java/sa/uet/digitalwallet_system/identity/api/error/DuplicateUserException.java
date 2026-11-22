package sa.uet.digitalwallet_system.identity.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class DuplicateUserException extends ModuleFailure {
    public DuplicateUserException(String field) {
        super(Kind.DUPLICATE_USER, "A user with this " + field + " already exists");
    }
}
