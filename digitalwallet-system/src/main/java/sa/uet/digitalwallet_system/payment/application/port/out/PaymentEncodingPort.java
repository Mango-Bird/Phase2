package sa.uet.digitalwallet_system.payment.application.port.out;

import sa.uet.digitalwallet_system.payment.application.PaymentIntent;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;

public interface PaymentEncodingPort {
    String requestHash(PaymentIntent intent);
    String successSnapshot(Transaction transaction);
}
