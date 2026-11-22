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
        "app.jwt.secret=transfer-endpoint-test-signing-key-2026", "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenService.class,
        TransactionService.class,
        sa.uet.digitalwallet_system.payment.application.PaymentFacade.class,
        sa.uet.digitalwallet_system.payment.application.PaymentExecutor.class,
        sa.uet.digitalwallet_system.payment.application.PaymentReplayReader.class,
        sa.uet.digitalwallet_system.payment.adapter.serialization.PaymentJsonEncoding.class,
        sa.uet.digitalwallet_system.payment.PaymentMvcStorageConfiguration.class, sa.uet.digitalwallet_system.wallet.application.WalletPostingService.class, sa.uet.digitalwallet_system.banking.application.BankAccountQueryService.class, GlobalExceptionHandler.class})
class TransferEndpointTest {
    private static final String PATH = "/api/v1/transactions/transfer";
    private static final String BODY = "{\"targetWalletNumber\":\"WAL20\",\"amount\":250,\"description\":\"Chuyen tien\"}";
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

    @Test void successDebitsSourceCreditsTargetAndRecordsTransfer() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        request(BODY, token()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Transfer successful"))
                .andExpect(jsonPath("$.data.type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.sourceWalletId").value(10))
                .andExpect(jsonPath("$.data.targetWalletId").value(20))
                .andExpect(jsonPath("$.data.amount").value(250))
                .andExpect(jsonPath("$.data.linkedBankAccountId").doesNotExist());
        var captor = org.mockito.ArgumentCaptor.forClass(Wallet.class);
        verify(saveWalletPort, times(2)).save(captor.capture());
        assertEquals(10L, captor.getAllValues().get(0).getId());
        assertEquals(750L, captor.getAllValues().get(0).getBalance());
        assertEquals(20L, captor.getAllValues().get(1).getId());
        assertEquals(750L, captor.getAllValues().get(1).getBalance());
        verify(saveTransactionPort).save(any(Transaction.class));
    }

    @Test void sameWalletReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(10, 1000, "ACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void insufficientBalanceReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 249, "ACTIVE"), wallet(20, 500, "ACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void inactiveSourceReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "INACTIVE"), wallet(20, 500, "ACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void inactiveTargetReturns400WithoutWrites() throws Exception {
        stub(wallet(10, 1000, "ACTIVE"), wallet(20, 500, "INACTIVE"));
        request(BODY, token()).andExpect(status().isBadRequest());
        noWrites();
    }

    @Test void missingTargetReturns404WithoutWrites() throws Exception {
        when(getWalletUseCase.getOwnedWallet(99L)).thenReturn(sa.uet.digitalwallet_system.wallet.application.WalletViews.fromDomain(wallet(10, 1000, "ACTIVE")));
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(wallet(10, 1000, "ACTIVE")));
        when(loadWalletPort.loadByUserId(99L)).thenReturn(Optional.of(wallet(10, 1000, "ACTIVE")));
        when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.empty());
        request(BODY, token()).andExpect(status().isNotFound());
        noWrites();
    }

    @Test void blankTargetReturns400() throws Exception {
        request("{\"targetWalletNumber\":\"  \",\"amount\":250}", token())
                .andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void zeroAmountReturns400() throws Exception {
        request("{\"targetWalletNumber\":\"WAL20\",\"amount\":0}", token())
                .andExpect(status().isBadRequest());
        noBusinessCalls();
    }

    @Test void missingAmountReturns400() throws Exception {
        request("{\"targetWalletNumber\":\"WAL20\"}", token())
                .andExpect(status().isBadRequest());
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

    private org.springframework.test.web.servlet.ResultActions request(String body, String jwt) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()).header("Authorization", "Bearer " + jwt).content(body));
    }

    private String token() { return tokenGenerator.issue(99L, "transfer-user", "ROLE_USER").value(); }

    private Wallet wallet(long id, long balance, String status) {
        return Wallet.builder().version(0L).createdAt(java.time.LocalDateTime.now()).updatedAt(java.time.LocalDateTime.now()).id(id).userId(id == 10 ? 99L : 100L).walletNumber("WAL" + id)
                .balance(balance).status(status).build();
    }

    private void stub(Wallet source, Wallet target) {
        when(getWalletUseCase.getOwnedWallet(99L)).thenReturn(sa.uet.digitalwallet_system.wallet.application.WalletViews.fromDomain(source));
        when(loadWalletPort.loadById(10L)).thenReturn(Optional.of(source));
        when(loadWalletPort.loadByUserId(99L)).thenReturn(Optional.of(source));
        when(loadWalletPort.loadByWalletNumber("WAL20")).thenReturn(Optional.of(target));
        when(loadWalletPort.loadById(target.getId())).thenReturn(Optional.of(target));
        when(saveTransactionPort.save(any(Transaction.class))).thenAnswer(call -> sa.uet.digitalwallet_system.payment.PaymentMvcStorageConfiguration.saved(call.getArgument(0)));
    }

    private void noWrites() { verifyNoInteractions(saveWalletPort, saveTransactionPort); }
    private void noBusinessCalls() { verifyNoInteractions(getWalletUseCase, loadWalletPort,
            saveWalletPort, loadLinkedBankPort, saveTransactionPort, loadTransactionPort); }
}
