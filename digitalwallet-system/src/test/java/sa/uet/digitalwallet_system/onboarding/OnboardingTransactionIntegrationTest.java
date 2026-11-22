package sa.uet.digitalwallet_system.onboarding;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import sa.uet.digitalwallet_system.identity.api.*;
import sa.uet.digitalwallet_system.onboarding.api.*;
import sa.uet.digitalwallet_system.wallet.adapter.legacy.WalletNumberGenerator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest
class OnboardingTransactionIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("c03_onboarding").withUsername("test_user").withPassword("test_password");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired RegisterAccountApi onboarding;
    @Autowired IdentityRegistrationApi identityRegistration;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean WalletNumberGenerator walletNumbers;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM linked_bank_accounts");
        jdbc.update("DELETE FROM wallets");
        jdbc.update("DELETE FROM users");
        reset(walletNumbers);
    }

    @Test void validSchemaCommitsUserAndInitialWalletTogether() {
        when(walletNumbers.next()).thenReturn("123456789012");
        RegisteredAccount account = onboarding.register(input("alice", "a@example.com"));
        assertThat(account.wallet().userId()).isEqualTo(account.user().id());
        assertThat(account.wallet().version()).isZero();
        assertThat(account.wallet().createdAt()).isNotNull();
        assertThat(account.wallet().updatedAt()).isNotNull();
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("wallets")).isEqualTo(1);
    }

    @Test void walletFailureRollsBackNewUserAndPreservesExistingBusinessData() {
        when(walletNumbers.next()).thenReturn("123456789012");
        onboarding.register(input("existing", "existing@example.com"));
        assertThatThrownBy(() -> onboarding.register(input("rollback", "rollback@example.com")))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("wallets")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='rollback'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT username FROM users LIMIT 1", String.class)).isEqualTo("existing");
    }

    @Test void identityWriteCannotBypassOnboardingTransactionBoundary() {
        assertThatThrownBy(() -> identityRegistration.createUser(input("direct", "direct@example.com")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("users")).isZero();
    }

    private RegisterUserInput input(String username, String email) {
        return new RegisterUserInput(username, "secret1", "Test User", email);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
