package sa.uet.digitalwallet_system.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import sa.uet.digitalwallet_system.platform.api.security.IssuedToken;

import static org.junit.jupiter.api.Assertions.*;

class JwtRoundTripTest {
    private static final String SECRET = "01234567890123456789012345678901";
    @Test void issuedTokenAuthenticatesWithSameClaims() throws Exception {
        IssuedToken token = new JwtTokenService(SECRET, 86400000L).issue(42L, "alice", "ROLE_USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token.value());
        try {
            new JwtAuthenticationFilter(SECRET).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
            assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
