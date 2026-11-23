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
    void upgradesVerifiedV1ToV2WithoutRewritingBusinessData() throws Exception {
        String url = newSchema("c04_upgrade");
        installLegacyFixture(url);
        List<String> before;
        try (Connection connection = connection(url)) { before = businessSnapshot(connection); }
        baselineVerifiedPhase1(url);
        var latest = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("2").baselineOnMigrate(false).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = connection(url)) {
            assertThat(businessSnapshot(connection)).isEqualTo(before);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM idempotency_requests")).isZero();
            assertThat(scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='2'")).isEqualTo(1);
            assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO idempotency_requests(actor_id,operation,idempotency_key,request_hash,status,created_at) VALUES (101,'TOPUP','invalid-result','" + "a".repeat(64) + "','COMPLETED',UTC_TIMESTAMP(6))");
                }
            }).hasMessageContaining("chk_idempotency_result");
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    @Test
    void freshSchemaThroughV2AppliesBothVersions() throws Exception {
        String url = newSchema("c04_fresh");
        var latest = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("2").baselineOnMigrate(false).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        try (Connection connection = connection(url)) {
            assertThat(scalar(connection, "SELECT COUNT(*) FROM idempotency_requests")).isZero();
            assertThat(scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2')")).isEqualTo(2);
        }
    }

    @Test
    void upgradesPopulatedV2DatabaseToV3WithoutChangingBusinessDataAndDoesNotRepeat() throws Exception {
        String url = newSchema("c06_upgrade");
        installLegacyFixture(url);
        baselineVerifiedPhase1(url);
        assertThat(flywayTarget(url, "2").migrate().migrationsExecuted).isEqualTo(1);
        List<String> before;
        try (Connection connection = connection(url)) { before = businessSnapshot(connection); }

        Flyway latest = flywayLatest(url);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = connection(url)) {
            assertThat(businessSnapshot(connection)).isEqualTo(before);
            assertThat(scalar(connection, "SELECT COUNT(*) FROM outbox_events")).isZero();
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='3'"))
                    .isEqualTo(1L);
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    @Test
    void freshLatestSchemaAppliesV1ThroughV3AndEnforcesOutboxContract() throws Exception {
        String url = newSchema("c06_fresh");
        Flyway latest = flywayLatest(url);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);

        try (Connection connection = connection(url)) {
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2','3')"))
                    .isEqualTo(3L);
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='outbox_events'
                      AND INDEX_NAME IN ('uq_outbox_aggregate_event','idx_outbox_dispatch','idx_outbox_lease')
                    """)).isEqualTo(8L);
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                    WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='outbox_events'
                      AND CONSTRAINT_TYPE='CHECK' AND ENFORCED='YES'
                    """)).isEqualTo(7L);
            assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO outbox_events(event_id,aggregate_type,aggregate_id,event_type,
                                schema_version,payload,status,attempts,next_attempt_at,created_at)
                            VALUES ('00000000-0000-0000-0000-000000000001','PAYMENT',401,
                                'payment.completed.v1',1,JSON_ARRAY(),'PENDING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                            """);
                }
            }).hasMessageContaining("chk_outbox_event");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO outbox_events(event_id,aggregate_type,aggregate_id,event_type,
                            schema_version,payload,status,attempts,next_attempt_at,created_at)
                        VALUES ('00000000-0000-0000-0000-000000000002','PAYMENT',402,
                            'payment.completed.v1',1,JSON_OBJECT(),'PENDING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                        """);
                statement.executeUpdate("""
                        UPDATE outbox_events SET status='CLAIMED', attempts=1,
                            lease_until=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND),
                            claim_token='00000000-0000-0000-0000-000000000099', claim_owner='worker-a'
                        WHERE event_id='00000000-0000-0000-0000-000000000002'
                        """);
            }
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM outbox_events
                    WHERE status='CLAIMED' AND attempts=1 AND lease_until IS NOT NULL
                      AND claim_token IS NOT NULL AND claim_owner='worker-a'
                    """)).isEqualTo(1L);
            assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            UPDATE outbox_events SET status='DONE', processed_at=UTC_TIMESTAMP(6)
                            WHERE event_id='00000000-0000-0000-0000-000000000002'
                            """);
                }
            }).hasMessageContaining("chk_outbox_claim");
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

        baselineVerifiedPhase1(url);
        var result = flyway(url).migrate();

        assertThat(result.migrationsExecuted).isZero();
        try (Connection connection = connection(url)) {
            assertThat(businessSnapshot(connection)).isEqualTo(before);
            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE' AND version = '1'"))
                    .isEqualTo(1L);
        }
    }

    @Test
    void baselineToolRejectsNotEnforcedCheckWithoutHistoryOrDataChanges() throws Exception {
        String url = newSchema("c02_check_not_enforced");
        installLegacyFixture(url);
        List<String> before;
        try (Connection connection = connection(url); var statement = connection.createStatement()) {
            before = businessSnapshot(connection);
            statement.execute("ALTER TABLE wallets ALTER CHECK chk_wallets_balance NOT ENFORCED");
            assertThat(Phase1SchemaVerifier.verify(connection))
                    .contains("check constraint not enforced: chk_wallets_balance "
                            + "expected ENFORCED=YES but was NO");
        }

        assertBaselineRejected(url, "check constraint not enforced: chk_wallets_balance", before);
    }

    @Test
    void baselineToolRejectsMissingPrimaryKeyAutoIncrementWithoutHistoryOrDataChanges()
            throws Exception {
        String url = newSchema("c02_missing_auto_increment");
        installLegacyFixture(url);
        List<String> before;
        try (Connection connection = connection(url); var statement = connection.createStatement()) {
            before = businessSnapshot(connection);
            statement.execute("ALTER TABLE transactions MODIFY COLUMN id BIGINT NOT NULL");
            assertThat(Phase1SchemaVerifier.verify(connection))
                    .contains("column auto_increment mismatch: transactions.id "
                            + "expected YES but was NO");
        }

        assertBaselineRejected(url, "column auto_increment mismatch: transactions.id", before);
    }

    @Test
    void baselineToolRejectsDefaultAndOnUpdateDriftWithoutHistoryOrDataChanges()
            throws Exception {
        String url = newSchema("c02_default_on_update_drift");
        installLegacyFixture(url);
        List<String> before;
        try (Connection connection = connection(url); var statement = connection.createStatement()) {
            before = businessSnapshot(connection);
            statement.execute("ALTER TABLE users ALTER COLUMN role SET DEFAULT 'role_user'");
            statement.execute("""
                    ALTER TABLE wallets
                    MODIFY COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    """);
            assertThat(Phase1SchemaVerifier.verify(connection))
                    .contains("column default mismatch: users.role expected ROLE_USER but was role_user")
                    .contains("column on-update mismatch: wallets.updated_at "
                            + "expected current_timestamp(3) but was <none>")
                    .contains("column extra mismatch: wallets.updated_at expected "
                            + "default_generated on update current_timestamp(3) "
                            + "but was default_generated");
        }

        assertBaselineRejected(url, "column default mismatch: users.role", before);
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
                    new EncodedResource(new ClassPathResource("db/legacy/phase1-schema.sql")),
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

    private void baselineVerifiedPhase1(String url) throws Exception {
        Phase1BaselineTool.baselineVerifiedPhase1(
                url, MYSQL.getUsername(), MYSQL.getPassword(), "UTC", true);
    }

    private void assertBaselineRejected(String url, String expectedError, List<String> before)
            throws Exception {
        assertThatThrownBy(() -> baselineVerifiedPhase1(url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase 1 schema verification failed")
                .hasMessageContaining(expectedError);
        try (Connection connection = connection(url)) {
            assertThat(flywayHistoryRows(connection)).isZero();
            assertThat(businessSnapshot(connection)).isEqualTo(before);
        }
    }

    private static long flywayHistoryRows(Connection connection) throws Exception {
        if (scalar(connection, """
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_schema_history'
                """) == 0) {
            return 0;
        }
        return scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history");
    }

    private static List<String> businessSnapshot(Connection connection) throws Exception {
        String sql = """
                SELECT CONCAT('user:', id, ':', username, ':', password_hash, ':', full_name,
                              ':', email, ':', role, ':',
                              DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'))
                  FROM users
                UNION ALL
                SELECT CONCAT('wallet:', id, ':', user_id, ':', wallet_number, ':', balance,
                              ':', status, ':', version, ':',
                              DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), ':',
                              DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f'))
                  FROM wallets
                UNION ALL
                SELECT CONCAT('bank:', id, ':', wallet_id, ':', bank_code, ':', bank_name,
                              ':', account_number_masked, ':', holder_name, ':', status, ':',
                              DATE_FORMAT(linked_at, '%Y-%m-%d %H:%i:%s.%f'), ':',
                              COALESCE(DATE_FORMAT(unlinked_at, '%Y-%m-%d %H:%i:%s.%f'), '<null>'))
                  FROM linked_bank_accounts
                UNION ALL
                SELECT CONCAT('tx:', id, ':', reference_code, ':', type, ':',
                              COALESCE(source_wallet_id, '<null>'), ':',
                              COALESCE(target_wallet_id, '<null>'), ':',
                              COALESCE(linked_bank_account_id, '<null>'), ':', amount, ':',
                              COALESCE(description, '<null>'), ':',
                              DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'))
                  FROM transactions
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
                .target("1") // C02 verifies the immutable four-table V1 baseline; C04 tests latest migration.
                .baselineVersion("1")
                .baselineDescription("Verified Phase 1 baseline")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
    }

    private static Flyway flywayTarget(String url, String target) {
        return Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
    }

    private static Flyway flywayLatest(String url) {
        return Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
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
