package sa.uet.digitalwallet_system.identity.api;

import java.time.Instant;

public record UserView(long id, String username, String fullName, String email, String role, Instant createdAt) {
}
