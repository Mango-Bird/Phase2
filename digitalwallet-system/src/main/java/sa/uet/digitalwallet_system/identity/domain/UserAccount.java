package sa.uet.digitalwallet_system.identity.domain;

import java.time.LocalDateTime;

public record UserAccount(Long id, String username, String passwordHash, String fullName,
                          String email, String role, LocalDateTime createdAt) {
    public static UserAccount newUser(String username, String passwordHash, String fullName,
                                      String email, LocalDateTime createdAt) {
        return new UserAccount(null, username, passwordHash, fullName, email, "ROLE_USER", createdAt);
    }
}
