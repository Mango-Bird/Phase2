package sa.uet.digitalwallet_system.wallet.api;

public record PostingWallet(long id, long userId, String walletNumber, long balance, String status, long version) {}
