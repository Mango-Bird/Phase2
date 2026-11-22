package sa.uet.digitalwallet_system.adapter.in.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import sa.uet.digitalwallet_system.platform.security.JwtAuthenticationFilter;
import sa.uet.digitalwallet_system.platform.security.SecurityConfig;
import sa.uet.digitalwallet_system.payment.adapter.in.web.controller.TransactionController;
import sa.uet.digitalwallet_system.platform.adapter.web.handler.GlobalExceptionHandler;
import sa.uet.digitalwallet_system.platform.security.JwtTokenService;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;
import sa.uet.digitalwallet_system.banking.application.port.out.LoadLinkedBankPort;
import sa.uet.digitalwallet_system.payment.application.port.out.LoadTransactionPort;
import sa.uet.digitalwallet_system.wallet.application.port.out.LoadWalletPort;
import sa.uet.digitalwallet_system.payment.application.port.out.SaveTransactionPort;
import sa.uet.digitalwallet_system.wallet.application.port.out.SaveWalletPort;
import sa.uet.digitalwallet_system.payment.application.service.TransactionService;
import sa.uet.digitalwallet_system.banking.domain.model.LinkedBankAccount;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TransactionController.class, properties = {
        "app.jwt.secret=withdraw-endpoint-test-signing-key-2026", "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenService.class,
        TransactionService.class, sa.uet.digitalwallet_system.wallet.application.WalletPostingService.class, sa.uet.digitalwallet_system.banking.application.BankAccountQueryService.class, GlobalExceptionHandler.class})
class WithdrawEndpointTest {
    private static final String PATH = "/api/v1/transactions/withdraw";
    private static final String BODY = "{\"linkedBankAccountId\":501,\"amount\":250,\"description\":\"Rut tien\"}";
    @Autowired WebApplicationContext context;
    @Autowired JwtTokenService tokenGenerator;
    @MockitoBean WalletQueryApi getWalletUseCase;
    @MockitoBean LoadWalletPort loadWalletPort;
    @MockitoBean SaveWalletPort saveWalletPort;
    @MockitoBean LoadLinkedBankPort loadLinkedBankPort;
    @MockitoBean SaveTransactionPort saveTransactionPort;
    @MockitoBean LoadTransactionPort loadTransactionPort;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void successfulWithdrawDebitsWalletAndCreatesLedgerEntry() throws Exception {
        stub(1000, "ACTIVE", "ACTIVE");
        request(BODY, token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Withdraw successful"))
                .andExpect(jsonPath("$.data.type").value("WITHDRAW"))
                .andExpect(jsonPath("$.data.sourceWalletId").value(10))
                .andExpect(jsonPath("$.data.linkedBankAccountId").value(501))
                .andExpect(jsonPath("$.data.amount").value(250));
        var wallet = org.mockito.ArgumentCaptor.forClass(Wallet.class);
        verify(saveWalletPort).save(wallet.capture());
        assertEquals(750L, wallet.getValue().getBalance());
        var tx = org.mockito.ArgumentCaptor.forClass(Transaction.class);
        verify(saveTransactionPort).save(tx.capture());
        assertEquals("WITHDRAW", tx.getValue().getType());
        assertEquals(10L, tx.getValue().getSourceWalletId());
        assertNull(tx.getValue().getTargetWalletId());
    }

    @Test void fullBalanceCanBeWithdrawn() throws Exception {
        stub(250, "ACTIVE", "ACTIVE");
        request(BODY, token()).andExpect(status().isOk());
        var wallet = org.mockito.ArgumentCaptor.forClass(Wallet.class);
        verify(saveWalletPort).save(wallet.capture());
        assertEquals(0L, wallet.getValue().getBalance());
    }

    @Test void insufficientBalanceReturns400WithoutWrites() throws Exception {
        stub(249, "ACTIVE", "ACTIVE");
        request(BODY, token()).andExpect(status().isBadRequest());
        verifyNoInteractions(saveWalletPort, saveTransactionPort);
    }

    @Test void missingBankIdReturns400() throws Exception {
        request("{\"amount\":250}", token()).andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void zeroAmountReturns400() throws Exception {
        request("{\"linkedBankAccountId\":501,\"amount\":0}", token()).andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void missingAmountReturns400() throws Exception {
        request("{\"linkedBankAccountId\":501}", token()).andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void missingTokenReturns401() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        noBusinessCalls();
    }

    @Test void invalidTokenReturns401() throws Exception {
        request(BODY, "invalid.jwt.token").andExpect(status().isUnauthorized());
        noBusinessCalls();
    }

    @Test void inactiveWalletReturns400WithoutWrites() throws Exception {
        stub(1000, "INACTIVE", "ACTIVE");
        request(BODY, token()).andExpect(status().isBadRequest());
        verifyNoInteractions(saveWalletPort, saveTransactionPort);
    }

    @Test void foreignBankReturns404WithoutWrites() throws Exception {
        stub(1000, "ACTIVE", null);
        request(BODY, token()).andExpect(status().isNotFound());
        verify(loadLinkedBankPort).loadByIdAndWalletId(501L, 10L);
        verifyNoInteractions(saveWalletPort, saveTransactionPort);
    }

    @Test void unlinkedBankMustNotBeUsableForWithdraw() throws Exception {
        stub(1000, "ACTIVE", "UNLINKED");
        int actual = request(BODY, token()).andReturn().getResponse().getStatus();
        assertEquals(400, actual);
        verifyNoInteractions(saveWalletPort, saveTransactionPort);
    }

    private org.springframework.test.web.servlet.ResultActions request(String body, String jwt) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwt).content(body));
    }

    private String token() { return tokenGenerator.issue(99L, "withdraw-user", "ROLE_USER").value(); }

    private void stub(long balance, String walletStatus, String bankStatus) {
        Wallet wallet = Wallet.builder().version(0L).createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now()).id(10L).userId(99L).walletNumber("WAL10")
                .balance(balance).status(walletStatus).build();
        when(getWalletUseCase.getOwnedWallet(99L)).thenReturn(sa.uet.digitalwallet_system.wallet.application.WalletViews.fromDomain(wallet));
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet));
        when(loadWalletPort.loadByUserId(99L)).thenReturn(Optional.of(wallet));
        when(loadLinkedBankPort.loadByIdAndWalletId(501L, 10L)).thenReturn(bankStatus == null
                ? Optional.empty() : Optional.of(LinkedBankAccount.builder()
                .id(501L).walletId(10L).status(bankStatus).build()));
        when(saveTransactionPort.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void noBusinessCalls() { verifyNoInteractions(getWalletUseCase, loadWalletPort,
            saveWalletPort, loadLinkedBankPort, saveTransactionPort, loadTransactionPort); }
}
