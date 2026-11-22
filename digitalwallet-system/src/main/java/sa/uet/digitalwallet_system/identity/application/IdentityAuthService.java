package sa.uet.digitalwallet_system.identity.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.identity.api.AuthResult;
import sa.uet.digitalwallet_system.identity.api.IdentityAuthApi;
import sa.uet.digitalwallet_system.identity.api.LoginInput;
import sa.uet.digitalwallet_system.identity.api.error.InvalidCredentialsException;
import sa.uet.digitalwallet_system.identity.application.port.out.UserAccountStore;
import sa.uet.digitalwallet_system.identity.domain.UserAccount;
import sa.uet.digitalwallet_system.platform.api.security.IssuedToken;
import sa.uet.digitalwallet_system.platform.api.security.PasswordHashPort;
import sa.uet.digitalwallet_system.platform.api.security.TokenIssuer;

@Service
@RequiredArgsConstructor
public class IdentityAuthService implements IdentityAuthApi {
    private final UserAccountStore userStore;
    private final PasswordHashPort passwordHash;
    private final TokenIssuer tokenIssuer;

    @Override
    @Transactional(readOnly = true)
    public AuthResult login(LoginInput input) {
        UserAccount user = userStore.findByUsername(input.username()).orElseThrow(InvalidCredentialsException::new);
        if (!passwordHash.matches(input.password(), user.passwordHash())) throw new InvalidCredentialsException();
        IssuedToken token = tokenIssuer.issue(user.id(), user.username(), user.role());
        return new AuthResult(token.value(), token.type(), token.expiresIn());
    }
}
