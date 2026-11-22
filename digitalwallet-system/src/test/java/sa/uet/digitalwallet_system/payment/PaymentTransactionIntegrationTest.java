package sa.uet.digitalwallet_system.payment;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import sa.uet.digitalwallet_system.payment.application.port.in.*;
import sa.uet.digitalwallet_system.payment.application.port.out.SaveTransactionPort;
import sa.uet.digitalwallet_system.wallet.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Actual module services, Flyway schema, JPA and one MySQL transaction manager. */
@Testcontainers
@SpringBootTest
class PaymentTransactionIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("c03_payment").withUsername("test_user").withPassword("test_password");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired TransferUseCase transfers;
    @Autowired TopupUseCase topups;
    @Autowired WithdrawUseCase withdrawals;
    @Autowired WalletPostingApi posting;
    @Autowired WalletCreationApi creation;
    @Autowired WalletQueryApi wallets;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean SaveTransactionPort history;

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM linked_bank_accounts");
        jdbc.update("DELETE FROM wallets");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,password_hash,full_name,email,role,created_at) VALUES "
                + "(101,'source','hash','Source','source@example.com','ROLE_USER',NOW()),"
                + "(202,'target','hash','Target','target@example.com','ROLE_USER',NOW())");
        jdbc.update("INSERT INTO wallets(id,user_id,wallet_number,balance,status,version,created_at,updated_at) VALUES "
                + "(11,101,'C03SOURCE',1000,'ACTIVE',0,NOW(),NOW()),"
                + "(22,202,'C03TARGET',500,'ACTIVE',0,NOW(),NOW())");
        jdbc.update("INSERT INTO linked_bank_accounts(id,wallet_id,bank_code,bank_name,account_number_masked,holder_name,status,linked_at) "
                + "VALUES (31,11,'BANK','Bank','******1234','Source','ACTIVE',NOW()),"
                + "(32,22,'BANK','Bank','******5678','Target','ACTIVE',NOW())");
    }

    @Test void transferCommitsBothBalancesAndSingleHistoryRow() {
        var result = transfers.transfer(new TransferCommand(101L, "C03TARGET", 250L, "transfer"));
        assertThat(result.getSourceWalletId()).isEqualTo(11L);
        assertThat(result.getTargetWalletId()).isEqualTo(22L);
        assertThat(balance(11)).isEqualTo(750);
        assertThat(balance(22)).isEqualTo(750);
        assertThat(countHistory()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM wallets", Long.class)).isEqualTo(1500);
        assertThat(wallets.getOwnedWallet(101).version()).isEqualTo(1);
        assertThat(wallets.getOwnedWallet(202).version()).isEqualTo(1);
    }

    @Test void mysqlHistoryFailureRollsBackBothBalancesAndVersions() {
        // Flush actual SQL first, then let the real history INSERT fail on MySQL.
        doAnswer(call -> {
            entityManager.flush();
            assertThat(balance(11)).isEqualTo(750);
            assertThat(balance(22)).isEqualTo(750);
            return call.callRealMethod();
        }).when(history).save(any());
        assertThatThrownBy(() -> transfers.transfer(new TransferCommand(101L, "C03TARGET", 250L, "x".repeat(256))))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Data too long for column 'description'");
        assertThat(balance(11)).isEqualTo(1000);
        assertThat(balance(22)).isEqualTo(500);
        assertThat(wallets.getOwnedWallet(101).version()).isZero();
        assertThat(wallets.getOwnedWallet(202).version()).isZero();
        assertThat(countHistory()).isZero();
    }

    @Test void bankOwnershipIsEnforcedAcrossModuleBoundary() {
        assertThatThrownBy(() -> topups.topup(new TopupCommand(101L, 32L, 100L, null)))
                .hasMessage("Linked bank account not found");
        jdbc.update("UPDATE linked_bank_accounts SET status='UNLINKED', unlinked_at=NOW() WHERE id=31");
        assertThatThrownBy(() -> withdrawals.withdraw(new WithdrawCommand(101L, 31L, 100L, null)))
                .hasMessage("Linked bank account is not active");
        assertThat(balance(11)).isEqualTo(1000);
        assertThat(countHistory()).isZero();
    }

    @Test void nativeWalletWritesRequireCallerTransaction() {
        assertThatThrownBy(() -> creation.createInitialWallet(303))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> posting.loadOwnedForPosting(101))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> posting.applyBalanceChange(new BalanceChange(11, 0, 1000, 900)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(balance(11)).isEqualTo(1000);
    }

    @Test void stalePostingCannotOverwriteCommittedBalance() {
        transaction.executeWithoutResult(status -> posting.applyBalanceChange(new BalanceChange(11, 0, 1000, 900)));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                posting.applyBalanceChange(new BalanceChange(11, 0, 1000, 800))))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(balance(11)).isEqualTo(900);
        assertThat(wallets.getOwnedWallet(101).version()).isEqualTo(1);
    }

    @Test void ownedQueryDoesNotExposeAnotherUsersWallet() {
        assertThatThrownBy(() -> wallets.getOwnedWalletById(101, 22)).hasMessage("Wallet not found");
        assertThat(wallets.getOwnedWalletById(101, 11).userId()).isEqualTo(101);
    }

    private long balance(long id) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE id=?", Long.class, id);
    }
    private long countHistory() { return jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class); }
}
