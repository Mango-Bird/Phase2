package sa.uet.digitalwallet_system.platform.api.security;

public record IssuedToken(String value, String type, long expiresIn) {
}
