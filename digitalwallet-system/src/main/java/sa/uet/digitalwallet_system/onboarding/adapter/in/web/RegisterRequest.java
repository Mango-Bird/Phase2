package sa.uet.digitalwallet_system.onboarding.adapter.in.web;

import jakarta.validation.constraints.*;

public record RegisterRequest(@NotBlank @Size(max=50) String username,
                              @NotBlank @Size(min=6,max=100) String password,
                              @NotBlank @Size(max=100) String fullName,
                              @NotBlank @Email @Size(max=100) String email) {
}
