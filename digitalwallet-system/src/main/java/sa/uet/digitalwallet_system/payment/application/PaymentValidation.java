package sa.uet.digitalwallet_system.payment.application;

import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;

public final class PaymentValidation {
    private PaymentValidation() {}
    public static void key(String key) {
        if (key == null) throw new PaymentFailure("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required", false);
        if (!key.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new PaymentFailure("IDEMPOTENCY_KEY_INVALID", "Invalid Idempotency-Key", false);
    }
    public static void payload(long amount, String description) {
        if (amount < 1) throw new PaymentFailure("INVALID_AMOUNT", "Amount must be greater than 0", false);
        if (description != null && description.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff))
            throw new PaymentFailure("INVALID_REQUEST", "Description must be valid Unicode", false);
        if (description != null && description.length() > 255)
            throw new PaymentFailure("INVALID_REQUEST", "Description must be at most 255 characters", false);
    }
    private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    public static void target(String target) {
        if (target == null || target.isBlank() || target.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff) || space(target.codePointAt(0)) || space(target.codePointBefore(target.length())))
            throw new PaymentFailure("INVALID_REQUEST", "Invalid target wallet number", false);
    }
}
