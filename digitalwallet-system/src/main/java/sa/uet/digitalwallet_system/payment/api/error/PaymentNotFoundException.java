package sa.uet.digitalwallet_system.payment.api.error;

import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;

public class PaymentNotFoundException extends ModuleFailure {
    public PaymentNotFoundException() { super(Kind.NOT_FOUND,"PAYMENT_NOT_FOUND","Payment not found",false); }
}
