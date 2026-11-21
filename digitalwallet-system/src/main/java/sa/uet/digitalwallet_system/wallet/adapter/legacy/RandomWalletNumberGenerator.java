package sa.uet.digitalwallet_system.wallet.adapter.legacy;

import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

@Component
class RandomWalletNumberGenerator implements WalletNumberGenerator {
    @Override public String next() {
        return Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L));
    }
}
