package sa.uet.digitalwallet_system.payment.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class PaymentFailure extends ModuleFailure {
    public PaymentFailure(String code, String message, boolean retryable) {
        super(Kind.INVALID_STATE, code, message, retryable);
    }
}
