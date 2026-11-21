package sa.uet.digitalwallet_system.platform.api.security;

public interface PasswordHashPort {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
}
