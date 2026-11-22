package sa.uet.digitalwallet_system.wallet.api;

/** Absolute balance derived by the caller from a versioned snapshot. */
public record BalanceChange(long walletId, long expectedVersion, long expectedBalance, long newBalance) {}
