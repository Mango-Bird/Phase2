package sa.uet.digitalwallet_system.identity.api;

public interface IdentityAuthApi {
    AuthResult login(LoginInput input);
}
