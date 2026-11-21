package sa.uet.digitalwallet_system.platform.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.platform.api.security.IssuedToken;
import sa.uet.digitalwallet_system.platform.api.security.TokenIssuer;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenService implements TokenIssuer {
    private final String secret;
    private final long expirationMs;

    public JwtTokenService(@Value("${app.jwt.secret}") String secret,
                           @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    @Override public IssuedToken issue(long userId, String username, String role) {
        Date now = new Date();
        String token = Jwts.builder().subject(String.valueOf(userId)).claim("username", username).claim("role", role)
                .issuedAt(now).expiration(new Date(now.getTime() + expirationMs)).signWith(key()).compact();
        return new IssuedToken(token, "Bearer", expirationMs);
    }

    SecretKey key() { return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }
}
