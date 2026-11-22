package sa.uet.digitalwallet_system.wallet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import sa.uet.digitalwallet_system.platform.adapter.web.handler.GlobalExceptionHandler;
import sa.uet.digitalwallet_system.platform.security.*;
import sa.uet.digitalwallet_system.wallet.adapter.in.web.controller.WalletController;
import sa.uet.digitalwallet_system.wallet.api.*;
import sa.uet.digitalwallet_system.wallet.api.error.WalletNotFoundException;

import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WalletController.class, properties = {
        "app.jwt.secret=wallet-contract-test-key-2026-0123456789012345",
        "app.jwt.expiration-ms=3600000"
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenService.class, GlobalExceptionHandler.class})
class WalletHttpContractTest {
    MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired JwtTokenService tokens;
    @MockitoBean WalletQueryApi wallets;

    @BeforeEach void securityChain() {
        // Register JWT only through the security chain, as in the endpoint regression suite.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void keepsLegacyJsonAndWallClockTimestampFormat() throws Exception {
        when(wallets.getOwnedWallet(101)).thenReturn(new WalletView(11, 101, "WALLET", 1000, "ACTIVE", 7,
                Instant.parse("2026-11-22T01:02:03Z"), Instant.parse("2026-11-22T02:03:04Z")));
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.userId").value(101))
                .andExpect(jsonPath("$.data.walletNumber").value("WALLET"))
                .andExpect(jsonPath("$.data.balance").value(1000))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-22T01:02:03"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-11-22T02:03:04"))
                .andExpect(jsonPath("$.data.version").doesNotExist());
    }

    @Test void missingWalletKeeps404Message() throws Exception {
        when(wallets.getOwnedWallet(101)).thenThrow(new WalletNotFoundException());
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Wallet not found"));
    }

    @Test void missingTokenDoesNotReadWallet() throws Exception {
        mvc.perform(get("/api/v1/wallets/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(wallets);
    }

    private String token() { return "Bearer " + tokens.issue(101, "owner", "ROLE_USER").value(); }
}
