package sa.uet.digitalwallet_system.platform.api.security;

public interface TokenIssuer {
    IssuedToken issue(long userId, String username, String role);
}
