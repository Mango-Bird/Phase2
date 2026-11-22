package sa.uet.digitalwallet_system.payment.api;

public record TopupInput(long linkedBankAccountId, long amount, String description) {}
