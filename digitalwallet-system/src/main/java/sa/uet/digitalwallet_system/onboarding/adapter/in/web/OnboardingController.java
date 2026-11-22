package sa.uet.digitalwallet_system.onboarding.adapter.in.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sa.uet.digitalwallet_system.identity.api.RegisterUserInput;
import sa.uet.digitalwallet_system.onboarding.api.*;
import sa.uet.digitalwallet_system.platform.api.web.ApiResponse;

@RestController @RequestMapping("/api/v1/auth") @RequiredArgsConstructor
public class OnboardingController {
    private final RegisterAccountApi registration;
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        RegisteredAccount account = registration.register(new RegisterUserInput(request.username(), request.password(),
                request.fullName(), request.email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("User registered successfully",
                UserResponse.from(account.user())));
    }
}
