package sa.uet.digitalwallet_system.identity.adapter.in.web;

public record AuthResponse(String accessToken, String tokenType, long expiresIn) {
}
