package sa.uet.digitalwallet_system.identity.api.error;

public class DuplicateUserException extends RuntimeException {
    public DuplicateUserException(String field) {
        super("A user with this " + field + " already exists");
    }
}
