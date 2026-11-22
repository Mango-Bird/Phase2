package sa.uet.digitalwallet_system.payment.api;

public record WithdrawInput(long linkedBankAccountId, long amount, String description) {}
