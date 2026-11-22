package sa.uet.digitalwallet_system.platform.api.error;

/** Framework-free failure contract; presentation owns the HTTP mapping. */
public class ModuleFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, INVALID_STATE, INSUFFICIENT_BALANCE, DUPLICATE_USER, INVALID_CREDENTIALS }
    private final Kind kind;
    private final String code;
    private final boolean retryable;
    protected ModuleFailure(Kind kind, String message) { this(kind, null, message, false); }
    protected ModuleFailure(Kind kind, String code, String message, boolean retryable) {
        super(message); this.kind = kind; this.code = code; this.retryable = retryable;
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
