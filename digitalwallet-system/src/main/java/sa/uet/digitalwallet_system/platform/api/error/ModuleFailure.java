package sa.uet.digitalwallet_system.platform.api.error;

/** Framework-free classification; presentation owns HTTP status mapping. */
public class ModuleFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, INVALID_STATE, INSUFFICIENT_BALANCE, DUPLICATE_USER, INVALID_CREDENTIALS }
    private final Kind kind;
    protected ModuleFailure(Kind kind, String message) { super(message); this.kind = kind; }
    public Kind kind() { return kind; }
}
