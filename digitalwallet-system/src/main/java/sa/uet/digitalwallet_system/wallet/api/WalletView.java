package sa.uet.digitalwallet_system.wallet.api;

import java.time.Instant;

public record WalletView(long id, long userId, String walletNumber, long balance, String status,
                         long version, Instant createdAt, Instant updatedAt) {
}
