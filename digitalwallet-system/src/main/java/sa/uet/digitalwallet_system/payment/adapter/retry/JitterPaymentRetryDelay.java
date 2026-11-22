package sa.uet.digitalwallet_system.payment.adapter.retry;

import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.payment.application.port.out.PaymentRetryDelayPort;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

@Component
public class JitterPaymentRetryDelay implements PaymentRetryDelayPort {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final java.util.function.Supplier<RandomGenerator> random;
    private final Sleeper sleeper;
    public JitterPaymentRetryDelay() { this.random = ThreadLocalRandom::current; this.sleeper = Thread::sleep; }
    public JitterPaymentRetryDelay(RandomGenerator random, Sleeper sleeper) { this.random = () -> random; this.sleeper = sleeper; }
    public void pauseBeforeRetry(int attempt) {
        if (attempt != 2 && attempt != 3) throw new IllegalArgumentException("Retry attempt must be 2 or 3");
        long millis = attempt == 2 ? random.get().nextLong(25, 51) : random.get().nextLong(50, 101);
        try { sleeper.sleep(millis); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PaymentFailure("RESOURCE_BUSY", "Retry interrupted; retry with the same key", true);
        }
    }
}
