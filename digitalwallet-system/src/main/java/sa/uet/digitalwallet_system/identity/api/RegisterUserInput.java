package sa.uet.digitalwallet_system.identity.api;

public record RegisterUserInput(String username, String password, String fullName, String email) {
}
