package sa.uet.digitalwallet_system.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}
