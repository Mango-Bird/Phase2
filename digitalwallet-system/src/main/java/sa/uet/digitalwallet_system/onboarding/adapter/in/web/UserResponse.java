package sa.uet.digitalwallet_system.onboarding.adapter.in.web;

import sa.uet.digitalwallet_system.identity.api.UserView;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record UserResponse(long id, String username, String fullName, String email, String role, LocalDateTime createdAt) {
    static UserResponse from(UserView user) {
        return new UserResponse(user.id(), user.username(), user.fullName(), user.email(), user.role(),
                LocalDateTime.ofInstant(user.createdAt(), ZoneOffset.UTC));
    }
}
