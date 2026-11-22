package sa.uet.digitalwallet_system.payment.api;

/** Durable JSON body is returned verbatim, including its original envelope timestamp. */
public record PaymentReceipt(int httpStatus, int responseVersion, String successBody,
                             String referenceCode, boolean replayed) {}
