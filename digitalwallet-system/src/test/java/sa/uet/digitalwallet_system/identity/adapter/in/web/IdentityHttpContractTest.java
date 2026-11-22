package sa.uet.digitalwallet_system.identity.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sa.uet.digitalwallet_system.platform.adapter.web.handler.GlobalExceptionHandler;
import sa.uet.digitalwallet_system.identity.api.*;
import sa.uet.digitalwallet_system.identity.api.error.InvalidCredentialsException;
import sa.uet.digitalwallet_system.identity.api.error.DuplicateUserException;
import sa.uet.digitalwallet_system.onboarding.adapter.in.web.OnboardingController;
import sa.uet.digitalwallet_system.onboarding.api.*;
import sa.uet.digitalwallet_system.platform.security.*;
import sa.uet.digitalwallet_system.wallet.api.WalletView;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {IdentityAuthController.class, OnboardingController.class}, properties = {
        "app.jwt.secret=01234567890123456789012345678901", "app.jwt.expiration-ms=86400000"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class IdentityHttpContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean IdentityAuthApi auth;
    @MockitoBean RegisterAccountApi registration;

    @Test void registerKeepsV1StatusAndBody() throws Exception {
        UserView user = new UserView(1L, "alice", "Alice", "a@example.com", "ROLE_USER", Instant.parse("2026-01-01T00:00:00Z"));
        Instant walletTime = Instant.parse("2026-01-01T00:00:00Z");
        when(registration.register(any())).thenReturn(new RegisteredAccount(user,
                new WalletView(2, 1, "123456789012", 0, "ACTIVE", 0, walletTime, walletTime)));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret1\",\"fullName\":\"Alice\",\"email\":\"a@example.com\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test void loginKeepsBearerShape() throws Exception {
        when(auth.login(any())).thenReturn(new AuthResult("jwt", "Bearer", 86400000L));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("jwt"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(86400000));
    }

    @Test void invalidCredentialsRemainUnauthorized() throws Exception {
        when(auth.login(any())).thenThrow(new InvalidCredentialsException());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"wrong12\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void duplicateRegistrationRemainsConflict() throws Exception {
        when(registration.register(any())).thenThrow(new DuplicateUserException("username"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret1\",\"fullName\":\"Alice\",\"email\":\"a@example.com\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }
}
