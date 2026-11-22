package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sa.uet.digitalwallet_system.payment.domain.MoneyRules;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import sa.uet.digitalwallet_system.wallet.api.error.InsufficientBalanceException;
import static org.assertj.core.api.Assertions.*;

class MoneyRulesTest {
    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void nonPositiveAmountsNeverReachArithmetic(long amount) {
        assertThatThrownBy(() -> MoneyRules.positive(amount)).isInstanceOfSatisfying(PaymentFailure.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_AMOUNT"));
        assertThatThrownBy(() -> MoneyRules.credit(100,amount)).isInstanceOf(PaymentFailure.class);
        assertThatThrownBy(() -> MoneyRules.debit(100,amount)).isInstanceOf(PaymentFailure.class);
    }
    @Test void nullAmountIsAnInputFailure() { assertThatThrownBy(() -> MoneyRules.positive(null)).isInstanceOf(PaymentFailure.class); }
    @Test void fullLongRangeHasNoArtificialMoneyCap() {
        assertThat(MoneyRules.credit(0,Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(MoneyRules.debit(Long.MAX_VALUE,Long.MAX_VALUE)).isZero();
    }
    @Test void overflowIsBusinessFailureInsteadOfWrappedBalance() {
        assertThatThrownBy(() -> MoneyRules.credit(Long.MAX_VALUE,1)).isInstanceOfSatisfying(PaymentFailure.class,
                error -> { assertThat(error.code()).isEqualTo("MONEY_OVERFLOW"); assertThat(error.retryable()).isFalse(); });
    }
    @Test void insufficientFundsCannotCreateANegativeBalance() {
        assertThatThrownBy(() -> MoneyRules.debit(100,101)).isInstanceOf(InsufficientBalanceException.class);
    }
    @Test void corruptNegativeBalanceCannotBeUsed() {
        assertThatThrownBy(() -> MoneyRules.credit(-1,1)).isInstanceOf(IllegalStateException.class);
    }
}
