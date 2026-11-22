package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import sa.uet.digitalwallet_system.payment.adapter.retry.JitterPaymentRetryDelay;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import java.util.random.RandomGenerator;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JitterPaymentRetryDelayTest {
    @Test void delaysUseInclusiveContractBoundsWithoutSleepingInUnitTests() throws Exception {
        var random=mock(RandomGenerator.class); var slept=new ArrayList<Long>();
        when(random.nextLong(25,51)).thenReturn(25L,50L);
        when(random.nextLong(50,101)).thenReturn(50L,100L);
        var delay=new JitterPaymentRetryDelay(random,slept::add);
        delay.pauseBeforeRetry(2); delay.pauseBeforeRetry(2); delay.pauseBeforeRetry(3); delay.pauseBeforeRetry(3);
        assertThat(slept).containsExactly(25L,50L,50L,100L);
    }
    @Test void interruptionPreservesThreadFlagAndDoesNotStartAnotherAttempt() {
        var delay=new JitterPaymentRetryDelay(new java.util.Random(1), millis -> { throw new InterruptedException(); });
        try {
            assertThatThrownBy(() -> delay.pauseBeforeRetry(2)).isInstanceOfSatisfying(PaymentFailure.class,
                    error -> assertThat(error.code()).isEqualTo("RESOURCE_BUSY"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
