package sa.uet.digitalwallet_system.payment.application.port.out;

/** Only a duplicate of the idempotency unique key, never an unrelated financial constraint. */
public class ClaimConflict extends RuntimeException {
    public ClaimConflict(Throwable cause) { super(cause); }
}
