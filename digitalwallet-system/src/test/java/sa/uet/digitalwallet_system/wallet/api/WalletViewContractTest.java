package sa.uet.digitalwallet_system.wallet.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WalletViewContractTest {
    @Test void matchesAcceptedC01PublicContract() {
        assertThat(Arrays.stream(WalletView.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("id", "userId", "walletNumber", "balance", "status", "version", "createdAt", "updatedAt");
        assertThat(WalletView.class.getRecordComponents()[6].getType()).isEqualTo(Instant.class);
        assertThat(WalletView.class.getRecordComponents()[7].getType()).isEqualTo(Instant.class);
    }
}
