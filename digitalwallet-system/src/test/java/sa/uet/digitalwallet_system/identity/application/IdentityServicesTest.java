package sa.uet.digitalwallet_system.identity.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import sa.uet.digitalwallet_system.identity.api.*;
import sa.uet.digitalwallet_system.identity.api.error.*;
import sa.uet.digitalwallet_system.identity.application.port.out.UserAccountStore;
import sa.uet.digitalwallet_system.identity.domain.UserAccount;
import sa.uet.digitalwallet_system.platform.api.security.*;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityServicesTest {
    @Mock UserAccountStore store;
    @Mock PasswordHashPort passwords;
    @Mock TokenIssuer tokens;

    @Test void createsUserWithoutCreatingWallet() {
        when(passwords.encode("secret1")).thenReturn("hash");
        when(store.save(any())).thenAnswer(i -> {
            UserAccount u = i.getArgument(0);
            return new UserAccount(7L, u.username(), u.passwordHash(), u.fullName(), u.email(), u.role(), u.createdAt());
        });
        UserView result = new IdentityRegistrationService(store, passwords)
                .createUser(new RegisterUserInput("alice", "secret1", "Alice", "a@example.com"));
        assertEquals(7L, result.id());
        assertEquals("ROLE_USER", result.role());
        verify(store).save(any());
    }

    @Test void rejectsDuplicateUsername() {
        when(store.existsByUsername("alice")).thenReturn(true);
        assertThrows(DuplicateUserException.class, () -> new IdentityRegistrationService(store, passwords)
                .createUser(new RegisterUserInput("alice", "secret1", "Alice", "a@example.com")));
        verify(store, never()).save(any());
    }

    @Test void authenticatesAndPreservesJwtMetadata() {
        UserAccount user = new UserAccount(7L, "alice", "hash", "Alice", "a@example.com", "ROLE_USER", LocalDateTime.now());
        when(store.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwords.matches("secret1", "hash")).thenReturn(true);
        when(tokens.issue(7L, "alice", "ROLE_USER")).thenReturn(new IssuedToken("jwt", "Bearer", 86400000L));
        AuthResult result = new IdentityAuthService(store, passwords, tokens).login(new LoginInput("alice", "secret1"));
        assertEquals(new AuthResult("jwt", "Bearer", 86400000L), result);
    }

    @Test void rejectsBadPassword() {
        UserAccount user = new UserAccount(7L, "alice", "hash", "Alice", "a@example.com", "ROLE_USER", LocalDateTime.now());
        when(store.findByUsername("alice")).thenReturn(Optional.of(user));
        assertThrows(InvalidCredentialsException.class, () ->
                new IdentityAuthService(store, passwords, tokens).login(new LoginInput("alice", "wrong")));
        verifyNoInteractions(tokens);
    }
}
