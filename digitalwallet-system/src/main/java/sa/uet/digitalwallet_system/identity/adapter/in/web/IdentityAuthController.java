package sa.uet.digitalwallet_system.identity.adapter.in.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sa.uet.digitalwallet_system.identity.api.*;
import sa.uet.digitalwallet_system.platform.api.web.ApiResponse;

@RestController @RequestMapping("/api/v1/auth") @RequiredArgsConstructor
public class IdentityAuthController {
    private final IdentityAuthApi identityAuth;
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = identityAuth.login(new LoginInput(request.username(), request.password()));
        return ResponseEntity.ok(ApiResponse.success("Login successful",
                new AuthResponse(result.accessToken(), result.tokenType(), result.expiresIn())));
    }
}
