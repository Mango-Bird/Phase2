package sa.uet.digitalwallet_system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DatabaseMigrationIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("migration_admin")
            .withUsername("test_user")
            .withPassword("test_password");

    @BeforeAll
    static void configureSessionPolicy() {
        assertThat(MYSQL.isRunning()).isTrue();
    }

    @Test
    void migratesAnEmptyDatabaseToV1() throws Exception {
        String url = newSchema("c02_empty");

        var result = flyway(url).migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        try (Connection connection = connection(url)) {
            assertThat(Phase1SchemaVerifier.verify(connection)).isEmpty();
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '1'"))
                    .isEqualTo(1L);
        }
    }

    @Test
    void explicitlyBaselinesVerifiedPhase1DataWithoutChangingIt() throws Exception {
        String url = newSchema("c02_legacy");
        installLegacyFixture(url);
        List<String> before;
        try (Connection connection = connection(url)) {
            assertThat(Phase1SchemaVerifier.verify(connection)).isEmpty();
            before = businessSnapshot(connection);
        }

        Flyway flyway = flyway(url);
        flyway.baseline();
        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isZero();
        try (Connection connection = connection(url)) {
            assertThat(businessSnapshot(connection)).isEqualTo(before);
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE' AND version = '1'"))
                    .isEqualTo(1L);
        }
    }

    @Test
    void restartDoesNotRepeatAnAppliedMigration() throws Exception {
        String url = newSchema("c02_restart");
        Flyway firstProcess = flyway(url);
        assertThat(firstProcess.migrate().migrationsExecuted).isEqualTo(1);

        Flyway restartedProcess = flyway(url);
        assertThat(restartedProcess.migrate().migrationsExecuted).isZero();

        try (Connection connection = connection(url)) {
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '1'"))
                    .isEqualTo(1L);
        }
    }

    @Test
    void rejectsAnIncompatibleNonEmptySchemaInsteadOfAutoBaselining() throws Exception {
        String url = newSchema("c02_wrong");
        try (Connection connection = connection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id bigint NOT NULL PRIMARY KEY, email varchar(99))");
            assertThat(Phase1SchemaVerifier.verify(connection))
                    .contains("missing table: wallets", "missing table: linked_bank_accounts",
                            "missing table: transactions")
                    .anyMatch(error -> error.startsWith("column mismatch: users.email"));
        }

        assertThatThrownBy(() -> flyway(url).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema")
                .hasMessageContaining("baseline");
    }

    private String newSchema(String schema) throws Exception {
        try (Connection connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            statement.execute("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO 'test_user'@'%'");
        }
        return jdbcUrl(schema);
    }

    private void installLegacyFixture(String url) throws Exception {
        try (Connection connection = connection(url)) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("db/migration/V1__phase1_baseline.sql")),
                    false, false, ScriptUtils.DEFAULT_COMMENT_PREFIX,
                    ScriptUtils.DEFAULT_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO users (id, username, password_hash, full_name, email, role, created_at)
                        VALUES (101, 'legacy-user', 'legacy-hash', 'Legacy User',
                                'legacy@example.test', 'ROLE_USER', '2026-10-15 15:00:00.123')
                        """);
                statement.executeUpdate("""
                        INSERT INTO wallets (id, user_id, wallet_number, balance, status, version,
                                             created_at, updated_at)
                        VALUES (201, 101, 'PHASE1-000201', 987654321, 'ACTIVE', 7,
                                '2026-10-15 15:01:00.123', '2026-10-15 15:02:00.456')
                        """);
                statement.executeUpdate("""
                        INSERT INTO linked_bank_accounts
                            (id, wallet_id, bank_code, bank_name, account_number_masked,
                             holder_name, status, linked_at, unlinked_at)
                        VALUES (301, 201, 'LEGACY', 'Legacy Bank', '******1234',
                                'Legacy User', 'ACTIVE', '2026-10-15 15:03:00.789', NULL)
                        """);
                statement.executeUpdate("""
                        INSERT INTO transactions
                            (id, reference_code, type, source_wallet_id, target_wallet_id,
                             linked_bank_account_id, amount, description, created_at)
                        VALUES (401, '00000000-0000-0000-0000-000000000401', 'TOPUP', NULL, 201,
                                301, 123456789, 'legacy fixture', '2026-10-15 15:04:00.321')
                        """);
            }
        }
    }

    private static List<String> businessSnapshot(Connection connection) throws Exception {
        String sql = """
                SELECT CONCAT('user:', id, ':', username, ':', DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'))
                  FROM users
                UNION ALL
                SELECT CONCAT('wallet:', id, ':', balance, ':', version) FROM wallets
                UNION ALL
                SELECT CONCAT('bank:', id, ':', status) FROM linked_bank_accounts
                UNION ALL
                SELECT CONCAT('tx:', id, ':', reference_code, ':', amount) FROM transactions
                ORDER BY 1
                """;
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            var rows = new java.util.ArrayList<String>();
            while (result.next()) {
                rows.add(result.getString(1));
            }
            return rows;
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .baselineDescription("Verified Phase 1 baseline")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
    }

    private static Connection adminConnection() throws Exception {
        Connection connection = DriverManager.getConnection(
                jdbcUrl("migration_admin"), "root", MYSQL.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("SET SESSION time_zone = '+00:00'");
            statement.execute("SET SESSION innodb_lock_wait_timeout = 2");
        }
        return connection;
    }

    private static Connection connection(String url) throws Exception {
        Connection connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("SET SESSION time_zone = '+00:00'");
            statement.execute("SET SESSION innodb_lock_wait_timeout = 2");
        }
        return connection;
    }

    private static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }
}
