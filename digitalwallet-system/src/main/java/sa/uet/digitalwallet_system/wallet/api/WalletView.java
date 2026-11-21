package sa.uet.digitalwallet_system.wallet.api;

public record WalletView(long id, long userId, String walletNumber, long balance, String status) {
}
