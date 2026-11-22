package sa.uet.digitalwallet_system.wallet.adapter.in.web.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import sa.uet.digitalwallet_system.wallet.api.WalletView;
import java.time.ZoneOffset;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
    private Long id;
    private Long userId;
    private String walletNumber;
    private Long balance;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WalletResponse fromDomain(WalletView wallet) {
        return WalletResponse.builder()
                .id(wallet.id())
                .userId(wallet.userId())
                .walletNumber(wallet.walletNumber())
                .balance(wallet.balance())
                .status(wallet.status())
                .createdAt(LocalDateTime.ofInstant(wallet.createdAt(), ZoneOffset.UTC))
                .updatedAt(LocalDateTime.ofInstant(wallet.updatedAt(), ZoneOffset.UTC))
                .build();
    }
}
