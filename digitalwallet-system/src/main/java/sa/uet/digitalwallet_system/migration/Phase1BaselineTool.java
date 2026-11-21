package sa.uet.digitalwallet_system.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import org.flywaydb.core.Flyway;

/**
 * Explicit, opt-in baseline command for an existing Phase 1 database.
 * This command never migrates, drops, truncates, or rewrites a business table.
 */
public final class Phase1BaselineTool {

    private Phase1BaselineTool() {
    }

    public static void main(String[] args) throws Exception {
        String url = required("SPRING_DATASOURCE_URL");
        String username = required("SPRING_DATASOURCE_USERNAME");
        String password = required("SPRING_DATASOURCE_PASSWORD");
        String legacyTimezone = required("C02_B_LEGACY_TIMEZONE");
        boolean backupConfirmed = "true".equalsIgnoreCase(required("C02_B_BACKUP_CONFIRMED"));
        baselineVerifiedPhase1(
                url, username, password, legacyTimezone, backupConfirmed);
    }

    static void baselineVerifiedPhase1(String url, String username, String password,
                                       String legacyTimezone, boolean backupConfirmed) throws Exception {
        if (!backupConfirmed) {
            throw new IllegalStateException("C02_B_BACKUP_CONFIRMED must be true after a restorable backup");
        }

        Snapshot before;
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            if (hasFlywayHistory(connection)) {
                throw new IllegalStateException("flyway_schema_history already exists; refusing to baseline again");
            }
            List<String> schemaErrors = Phase1SchemaVerifier.verify(connection);
            if (!schemaErrors.isEmpty()) {
                throw new IllegalStateException("Phase 1 schema verification failed:\n- "
                        + String.join("\n- ", schemaErrors));
            }
            before = Snapshot.read(connection);
            System.out.println("Verified Phase 1 schema; declared legacy timezone=" + legacyTimezone);
            System.out.println("Database timezone evidence: " + timezoneEvidence(connection));
            System.out.println("Business data before baseline: " + before);
        }

        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("1")
                .baselineDescription("Verified Phase 1 baseline")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load()
                .baseline();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            Snapshot after = Snapshot.read(connection);
            if (!before.equals(after)) {
                throw new IllegalStateException("Business data changed while baselining: before="
                        + before + ", after=" + after);
            }
            System.out.println("Business data after baseline:  " + after);
            System.out.println("Baseline V1 recorded successfully; application migration was not run.");
        }
    }

    private static boolean hasFlywayHistory(Connection connection) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flyway_schema_history'
                """;
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1) != 0;
        }
    }

    private static String timezoneEvidence(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT @@global.time_zone, @@session.time_zone, @@system_time_zone")) {
            result.next();
            return "global=" + result.getString(1) + ", session=" + result.getString(2)
                    + ", system=" + result.getString(3);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    private record Snapshot(long users, long wallets, long linkedBanks, long transactions,
                            long walletBalanceSum, long transactionAmountSum,
                            long maxUserId, long maxWalletId, long maxLinkedBankId,
                            long maxTransactionId) {
        static Snapshot read(Connection connection) throws Exception {
            return new Snapshot(
                    scalar(connection, "SELECT COUNT(*) FROM users"),
                    scalar(connection, "SELECT COUNT(*) FROM wallets"),
                    scalar(connection, "SELECT COUNT(*) FROM linked_bank_accounts"),
                    scalar(connection, "SELECT COUNT(*) FROM transactions"),
                    scalar(connection, "SELECT COALESCE(SUM(balance), 0) FROM wallets"),
                    scalar(connection, "SELECT COALESCE(SUM(amount), 0) FROM transactions"),
                    scalar(connection, "SELECT COALESCE(MAX(id), 0) FROM users"),
                    scalar(connection, "SELECT COALESCE(MAX(id), 0) FROM wallets"),
                    scalar(connection, "SELECT COALESCE(MAX(id), 0) FROM linked_bank_accounts"),
                    scalar(connection, "SELECT COALESCE(MAX(id), 0) FROM transactions"));
        }

        private static long scalar(Connection connection, String sql) throws Exception {
            try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
