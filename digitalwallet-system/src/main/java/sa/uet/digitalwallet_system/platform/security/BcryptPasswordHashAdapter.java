package sa.uet.digitalwallet_system.platform.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.platform.api.security.PasswordHashPort;

@Component
@RequiredArgsConstructor
class BcryptPasswordHashAdapter implements PasswordHashPort {
    private final PasswordEncoder encoder;
    @Override public String encode(String rawPassword) { return encoder.encode(rawPassword); }
    @Override public boolean matches(String rawPassword, String encodedPassword) { return encoder.matches(rawPassword, encodedPassword); }
}
