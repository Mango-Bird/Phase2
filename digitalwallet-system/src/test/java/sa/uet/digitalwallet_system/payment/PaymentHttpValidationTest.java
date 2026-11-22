package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;
import sa.uet.digitalwallet_system.payment.adapter.in.web.controller.TransactionController;
import sa.uet.digitalwallet_system.payment.adapter.in.web.StrictPaymentBodyAdvice;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.port.in.GetTransactionsUseCase;
import sa.uet.digitalwallet_system.platform.adapter.web.handler.GlobalExceptionHandler;
import sa.uet.digitalwallet_system.platform.security.*;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TransactionController.class, properties = {
        "app.jwt.secret=payment-validation-signing-key-2026-0123456789", "app.jwt.expiration-ms=3600000"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenService.class, GlobalExceptionHandler.class, StrictPaymentBodyAdvice.class})
class PaymentHttpValidationTest {
    @Autowired WebApplicationContext context;
    @Autowired JwtTokenService tokens;
    @MockitoBean PaymentCommandApi payments;
    @MockitoBean WalletQueryApi wallets;
    @MockitoBean GetTransactionsUseCase history;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    String token() { return "Bearer " + tokens.issue(101, "source", "ROLE_USER").value(); }
    static final String BODY = "{\"linkedBankAccountId\":31,\"amount\":100}";

    @Test void missingKeyHasStableCodeAndTraceWithoutBusinessCalls() throws Exception {
        mvc.perform(post("/api/v1/transactions/topup").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(payments, wallets, history);
    }
    @ParameterizedTest @ValueSource(strings = {"", " key", "key ", "a b", "key,other", "khóa", "abc/def"})
    void malformedKeysAreRejectedBeforeBusiness(String key) throws Exception {
        mvc.perform(post("/api/v1/transactions/topup").header("Authorization", token()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
        verifyNoInteractions(payments);
    }
    @Test void tooLongKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/transactions/topup").header("Authorization", token()).header("Idempotency-Key", "a".repeat(65))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"linkedBankAccountId\":31,\"amount\":100,\"amount\":200}",
        "{\"linkedBankAccountId\":31,\"amount\":100,\"extra\":1}",
        "{\"linkedBankAccountId\":31,\"amount\":\"100\"}",
        "{\"linkedBankAccountId\":31,\"amount\":100.0}",
        "{\"linkedBankAccountId\":31,\"amount\":1e2}",
        "{\"linkedBankAccountId\":31,\"amount\":9223372036854775808}",
        "{\"linkedBankAccountId\":\"31\",\"amount\":100}",
        "{\"linkedBankAccountId\":31,\"amount\":100,\"description\":42}",
        "{\"linkedBankAccountId\":31,\"amount\":100} {}", "[]", "null"})
    void strictJsonRejectsAmbiguousOrCoercedPayload(String body) throws Exception {
        mvc.perform(post("/api/v1/transactions/topup").header("Authorization", token()).header("Idempotency-Key", "strict")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }
    @ParameterizedTest @ValueSource(strings = {"topup", "withdraw", "transfer"})
    void duplicateAndUnknownFieldsAreRejectedForEveryCommand(String operation) throws Exception {
        String field = operation.equals("transfer") ? "\"targetWalletNumber\":\"TARGET\"" : "\"linkedBankAccountId\":31";
        for (String suffix : java.util.List.of(",\"amount\":100,\"amount\":200}", ",\"amount\":100,\"unexpected\":1}")) {
            mvc.perform(post("/api/v1/transactions/" + operation).header("Authorization", token()).header("Idempotency-Key", "strict")
                    .contentType(MediaType.APPLICATION_JSON).content("{" + field + suffix)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(payments);
    }
    @Test void validPayloadAndMaxLengthKeyReachApiWithAuthenticatedActor() throws Exception {
        when(payments.topup(eq(101L), eq("a".repeat(64)), any())).thenReturn(new PaymentReceipt(200, 1, "{\"status\":200}", "ref", false));
        mvc.perform(post("/api/v1/transactions/topup").header("Authorization", token()).header("Idempotency-Key", "a".repeat(64))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        verify(payments).topup(101L, "a".repeat(64), new TopupInput(31,100,null));
    }
}
