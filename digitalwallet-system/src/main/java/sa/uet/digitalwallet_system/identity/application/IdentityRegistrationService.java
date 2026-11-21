package sa.uet.digitalwallet_system.identity.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.identity.api.IdentityRegistrationApi;
import sa.uet.digitalwallet_system.identity.api.RegisterUserInput;
import sa.uet.digitalwallet_system.identity.api.UserView;
import sa.uet.digitalwallet_system.identity.api.error.DuplicateUserException;
import sa.uet.digitalwallet_system.identity.application.port.out.UserAccountStore;
import sa.uet.digitalwallet_system.identity.domain.UserAccount;
import sa.uet.digitalwallet_system.platform.api.security.PasswordHashPort;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class IdentityRegistrationService implements IdentityRegistrationApi {
    private final UserAccountStore userStore;
    private final PasswordHashPort passwordHash;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserView createUser(RegisterUserInput input) {
        if (userStore.existsByUsername(input.username())) throw new DuplicateUserException("username");
        if (userStore.existsByEmail(input.email())) throw new DuplicateUserException("email");
        UserAccount saved = userStore.save(UserAccount.newUser(input.username(), passwordHash.encode(input.password()),
                input.fullName(), input.email(), LocalDateTime.now(ZoneOffset.UTC)));
        return toView(saved);
    }

    private UserView toView(UserAccount user) {
        return new UserView(user.id(), user.username(), user.fullName(), user.email(), user.role(),
                user.createdAt().toInstant(ZoneOffset.UTC));
    }
}
