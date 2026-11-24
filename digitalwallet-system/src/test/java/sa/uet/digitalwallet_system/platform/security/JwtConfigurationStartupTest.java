package sa.uet.digitalwallet_system.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationStartupTest {
    @Test
    void startupFailsWhenJwtSecretIsMissing() {
        assertThatThrownBy(() -> start(new MockEnvironment()))
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseMessage("Could not resolve placeholder 'app.jwt.secret' in value \"${app.jwt.secret}\"");
    }

    @Test
    void startupFailsWhenJwtSecretIsBlankOrTooShort() {
        assertThatThrownBy(() -> start(environmentWith("   ")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("JWT signing secret is required");
        assertThatThrownBy(() -> start(environmentWith("too-short")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("JWT signing secret must contain at least 32 UTF-8 bytes");
    }

    @Test
    void explicitTestOrDevelopmentSecretStartsAndPreservesJwtSemantics() {
        try (AnnotationConfigApplicationContext context = start(
                environmentWith("test-or-dev-only-key-0123456789abcdef"))) {
            JwtTokenService tokens = context.getBean(JwtTokenService.class);
            JwtAuthenticationFilter filter = context.getBean(JwtAuthenticationFilter.class);
            assertThat(tokens).isNotNull();
            assertThat(filter).isNotNull();
            assertThat(tokens.issue(7L, "alice", "ROLE_USER").type()).isEqualTo("Bearer");
        }
    }

    private MockEnvironment environmentWith(String secret) {
        return new MockEnvironment().withProperty("app.jwt.secret", secret)
                .withProperty("app.jwt.expiration-ms", "86400000");
    }

    private AnnotationConfigApplicationContext start(MockEnvironment environment) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(ProbeConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        JwtTokenService jwtTokenService(@Value("${app.jwt.secret}") String secret,
                                        @Value("${app.jwt.expiration-ms:86400000}") long expiration) {
            return new JwtTokenService(secret, expiration);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(@Value("${app.jwt.secret}") String secret) {
            return new JwtAuthenticationFilter(secret);
        }
    }
}
