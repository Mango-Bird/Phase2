package sa.uet.digitalwallet_system.identity.api;

public interface IdentityRegistrationApi {
    UserView createUser(RegisterUserInput input);
}
