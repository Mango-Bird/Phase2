package sa.uet.digitalwallet_system.onboarding.api;

import sa.uet.digitalwallet_system.identity.api.RegisterUserInput;

public interface RegisterAccountApi {
    RegisteredAccount register(RegisterUserInput input);
}
