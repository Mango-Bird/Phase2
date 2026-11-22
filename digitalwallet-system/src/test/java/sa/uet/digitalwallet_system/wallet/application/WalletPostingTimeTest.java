package sa.uet.digitalwallet_system.wallet.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import sa.uet.digitalwallet_system.wallet.api.BalanceChange;
import sa.uet.digitalwallet_system.wallet.application.port.out.LoadWalletPort;
import sa.uet.digitalwallet_system.wallet.application.port.out.SaveWalletPort;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WalletPostingTimeTest {
    @ParameterizedTest
    @ValueSource(strings = {"Asia/Bangkok", "Pacific/Honolulu"})
    void balanceUpdatePublishesActualInstantRegardlessOfJvmZone(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var load = mock(LoadWalletPort.class);
            var save = mock(SaveWalletPort.class);
            Instant before = Instant.now();
            LocalDateTime created = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
            Wallet wallet = Wallet.builder().id(11L).userId(101L).walletNumber("TIMEZONE")
                    .balance(1000L).status("ACTIVE").version(0L).createdAt(created).updatedAt(created).build();
            when(load.loadById(11L)).thenReturn(Optional.of(wallet));

            new WalletPostingService(load, save).applyBalanceChange(new BalanceChange(11, 0, 1000, 900));

            Instant after = Instant.now();
            var saved = ArgumentCaptor.forClass(Wallet.class);
            verify(save).save(saved.capture());
            assertThat(WalletViews.fromDomain(saved.getValue()).updatedAt()).isBetween(before, after);
            assertThat(saved.getValue().getBalance()).isEqualTo(900);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
