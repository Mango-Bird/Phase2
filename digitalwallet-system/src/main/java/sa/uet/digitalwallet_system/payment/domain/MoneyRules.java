package sa.uet.digitalwallet_system.payment.domain;

import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import sa.uet.digitalwallet_system.wallet.api.error.InsufficientBalanceException;

/** Integer VND only. Never permit wrapped arithmetic to reach persistence. */
public final class MoneyRules {
    private MoneyRules() {}
    public static long positive(Long amount) {
        if (amount == null || amount < 1) throw new PaymentFailure("INVALID_AMOUNT", "Amount must be greater than 0", false);
        return amount;
    }
    public static long credit(long balance, long amount) {
        positive(amount); validBalance(balance);
        try { return Math.addExact(balance, amount); }
        catch (ArithmeticException ex) { throw new PaymentFailure("MONEY_OVERFLOW", "Wallet balance would exceed the supported amount", false); }
    }
    public static long debit(long balance, long amount) {
        positive(amount); validBalance(balance);
        if (balance < amount) throw new InsufficientBalanceException();
        return Math.subtractExact(balance, amount);
    }
    private static void validBalance(long balance) {
        if (balance < 0) throw new IllegalStateException("Persisted wallet balance must be non-negative");
    }
}
