package sa.uet.digitalwallet_system.payment.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.application.port.out.PaymentFlushPort;

@Component
@RequiredArgsConstructor
public class JpaPaymentFlush implements PaymentFlushPort {
    private final EntityManager entityManager;
    @Transactional(propagation = Propagation.MANDATORY)
    public void flush() { entityManager.flush(); }
}
