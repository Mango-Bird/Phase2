package sa.uet.digitalwallet_system.application.service;

import org.junit.jupiter.api.Test;
import sa.uet.digitalwallet_system.wallet.application.service.WalletService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sa.uet.digitalwallet_system.wallet.application.port.out.LoadWalletPort;
import sa.uet.digitalwallet_system.wallet.api.error.WalletNotFoundException;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private LoadWalletPort loadWalletPort;

    @InjectMocks
    private WalletService walletService;

    @Test
    void getWallet_Success() {
        LocalDateTime now = LocalDateTime.now();
        Wallet wallet = Wallet.builder()
                .id(10L)
                .userId(1L)
                .walletNumber("123456789012")
                .balance(500000L)
                .status("ACTIVE")
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();

        when(loadWalletPort.loadByUserId(1L)).thenReturn(Optional.of(wallet));

        sa.uet.digitalwallet_system.wallet.api.WalletView result = walletService.getOwnedWallet(1L);

        assertNotNull(result);
        assertEquals(10L, result.id());
        assertEquals("123456789012", result.walletNumber());
        assertEquals(500000L, result.balance());
    }

    @Test
    void getWallet_NotFound_ThrowsException() {
        when(loadWalletPort.loadByUserId(999L)).thenReturn(Optional.empty());

        assertThrows(WalletNotFoundException.class, () -> walletService.getOwnedWallet(999L));
    }
}
