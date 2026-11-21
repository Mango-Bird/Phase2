package sa.uet.digitalwallet_system.onboarding.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.identity.api.IdentityRegistrationApi;
import sa.uet.digitalwallet_system.identity.api.RegisterUserInput;
import sa.uet.digitalwallet_system.identity.api.UserView;
import sa.uet.digitalwallet_system.onboarding.api.RegisterAccountApi;
import sa.uet.digitalwallet_system.onboarding.api.RegisteredAccount;
import sa.uet.digitalwallet_system.wallet.api.WalletCreationApi;

@Service @RequiredArgsConstructor
public class RegisterAccountService implements RegisterAccountApi {
    private final IdentityRegistrationApi identityRegistration;
    private final WalletCreationApi walletCreation;

    @Override @Transactional
    public RegisteredAccount register(RegisterUserInput input) {
        UserView user = identityRegistration.createUser(input);
        return new RegisteredAccount(user, walletCreation.createInitialWallet(user.id()));
    }
}
