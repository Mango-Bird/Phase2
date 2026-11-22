package sa.uet.digitalwallet_system.payment.api;

public record TransferInput(String targetWalletNumber, long amount, String description) {}
