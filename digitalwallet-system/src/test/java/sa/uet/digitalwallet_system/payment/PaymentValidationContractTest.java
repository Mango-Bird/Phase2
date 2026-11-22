package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sa.uet.digitalwallet_system.payment.application.PaymentValidation;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import static org.assertj.core.api.Assertions.*;

class PaymentValidationContractTest {
    @ParameterizedTest @ValueSource(strings = {" TARGET", "TARGET ", "\tTARGET", "TARGET\n", "\u00a0TARGET"})
    void targetCannotHaveLeadingOrTrailingWhitespace(String value) {
        assertThatThrownBy(() -> PaymentValidation.target(value)).isInstanceOf(PaymentFailure.class);
    }
    @Test void unpairedSurrogatesCannotCollapseIntoTheSameUtf8Hash() {
        for (char invalid : new char[]{(char)0xd800, (char)0xdfff}) {
            assertThatThrownBy(() -> PaymentValidation.payload(1, String.valueOf(invalid))).isInstanceOf(PaymentFailure.class);
        }
        assertThatCode(() -> PaymentValidation.payload(1, "Chuyển tiền 😀")).doesNotThrowAnyException();
    }
}
