package sa.uet.digitalwallet_system.platform.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.SecurityException;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j @Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final String secret;
    public JwtAuthenticationFilter(@Value("${app.jwt.secret}") String secret) {
        this.secret = JwtSecretPolicy.requireValid(secret);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) authenticate(header.substring(7));
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            String role = claims.get("role", String.class);
            if (role != null && !role.isBlank()) {
                String prefixed = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                authorities.add(new SimpleGrantedAuthority(prefixed));
                authorities.add(new SimpleGrantedAuthority(prefixed.substring(5)));
            }
            if (claims.getSubject() != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        Long.parseLong(claims.getSubject()), null, authorities));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT authentication rejected: {}", ex.getMessage());
        }
    }
}
