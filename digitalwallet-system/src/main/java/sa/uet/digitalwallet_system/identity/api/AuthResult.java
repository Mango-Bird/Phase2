package sa.uet.digitalwallet_system.identity.api;

public record AuthResult(String accessToken, String tokenType, long expiresIn) {
}
