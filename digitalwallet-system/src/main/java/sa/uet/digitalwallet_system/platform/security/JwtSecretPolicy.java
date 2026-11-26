package sa.uet.digitalwallet_system.platform.security;

import java.nio.charset.StandardCharsets;

final class JwtSecretPolicy {
    private static final int MINIMUM_BYTES = 32;

    private JwtSecretPolicy() {
    }

    static String requireValid(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT signing secret is required");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_BYTES) {
            throw new IllegalStateException("JWT signing secret must contain at least 32 UTF-8 bytes");
        }
        return secret;
    }
}
