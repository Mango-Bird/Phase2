package sa.uet.digitalwallet_system.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration @EnableWebSecurity @RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtFilter;
    @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint((r, response, x) -> {
                    authError(response, r.getRequestURI(), 401, "Unauthorized", "Authentication required");
                }).accessDeniedHandler((r, response, x) -> {
                    authError(response, r.getRequestURI(), 403, "Forbidden", "Access denied");
                }))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper ERROR_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static void authError(HttpServletResponse response, String path, int status, String error, String message) throws java.io.IOException {
        String trace = java.util.UUID.randomUUID().toString();
        response.setContentType("application/json"); response.setStatus(status);
        response.getWriter().write(ERROR_JSON.writeValueAsString(java.util.Map.of(
                "timestamp", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).toString(),
                "status", status, "error", error, "message", message, "path", path,
                "code", status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN", "retryable", false, "traceId", trace)));
    }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
