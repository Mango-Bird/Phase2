package sa.uet.digitalwallet_system.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Phase1SchemaVerifier {

    private static final Map<String, List<Column>> EXPECTED_COLUMNS = expectedColumns();
    private static final Map<String, Map<String, String>> EXPECTED_INDEXES = Map.of(
            "users", Map.of("PRIMARY", "id", "uq_users_username", "username", "uq_users_email", "email"),
            "wallets", Map.of("PRIMARY", "id", "uq_wallets_number", "wallet_number",
                    "uq_wallets_user", "user_id"),
            "linked_bank_accounts", Map.of("PRIMARY", "id", "idx_linked_wallet_status", "wallet_id,status"),
            "transactions", Map.of("PRIMARY", "id", "uq_tx_reference", "reference_code",
                    "fk_tx_bank", "linked_bank_account_id", "idx_tx_source_created", "source_wallet_id,created_at",
                    "idx_tx_target_created", "target_wallet_id,created_at")
    );
    private static final Map<String, Set<String>> EXPECTED_CONSTRAINTS = Map.of(
            "users", Set.of("PRIMARY", "uq_users_username", "uq_users_email", "chk_users_role"),
            "wallets", Set.of("PRIMARY", "uq_wallets_number", "uq_wallets_user",
                    "fk_wallets_user", "chk_wallets_balance", "chk_wallets_status"),
            "linked_bank_accounts", Set.of("PRIMARY", "fk_linked_wallet", "chk_linked_status",
                    "chk_linked_unlinked_at"),
            "transactions", Set.of("PRIMARY", "uq_tx_reference", "fk_tx_bank", "fk_tx_source",
                    "fk_tx_target", "chk_tx_amount", "chk_tx_shape", "chk_tx_type")
    );
    private static final Map<String, Map<String, String>> EXPECTED_FOREIGN_KEYS = Map.of(
            "users", Map.of(),
            "wallets", Map.of("fk_wallets_user", "user_id->users.id"),
            "linked_bank_accounts", Map.of("fk_linked_wallet", "wallet_id->wallets.id"),
            "transactions", Map.of("fk_tx_bank", "linked_bank_account_id->linked_bank_accounts.id",
                    "fk_tx_source", "source_wallet_id->wallets.id",
                    "fk_tx_target", "target_wallet_id->wallets.id")
    );
    private static final Map<String, String> EXPECTED_CHECKS = Map.ofEntries(
            Map.entry("chk_users_role", normalized("role IN ('ROLE_USER', 'ROLE_ADMIN')")),
            Map.entry("chk_wallets_balance", normalized("balance >= 0")),
            Map.entry("chk_wallets_status", normalized("status IN ('ACTIVE', 'FROZEN', 'CLOSED')")),
            Map.entry("chk_linked_status", normalized("status IN ('ACTIVE', 'UNLINKED')")),
            Map.entry("chk_linked_unlinked_at", normalized("""
                    (status = 'ACTIVE' AND unlinked_at IS NULL)
                    OR (status = 'UNLINKED' AND unlinked_at IS NOT NULL)
                    """)),
            Map.entry("chk_tx_amount", normalized("amount > 0")),
            Map.entry("chk_tx_type", normalized("type IN ('TRANSFER', 'TOPUP', 'WITHDRAW')")),
            Map.entry("chk_tx_shape", normalized("""
                    (type = 'TRANSFER' AND source_wallet_id IS NOT NULL AND target_wallet_id IS NOT NULL
                      AND source_wallet_id <> target_wallet_id AND linked_bank_account_id IS NULL)
                    OR (type = 'TOPUP' AND source_wallet_id IS NULL AND target_wallet_id IS NOT NULL
                      AND linked_bank_account_id IS NOT NULL)
                    OR (type = 'WITHDRAW' AND source_wallet_id IS NOT NULL AND target_wallet_id IS NULL
                      AND linked_bank_account_id IS NOT NULL)
                    """))
    );

    private Phase1SchemaVerifier() {
    }

    public static List<String> verify(Connection connection) throws SQLException {
        List<String> errors = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();

        Set<String> actualTables = tableNames(connection, catalog);
        Set<String> expectedTables = EXPECTED_COLUMNS.keySet();
        expectedTables.stream().filter(table -> !actualTables.contains(table))
                .forEach(table -> errors.add("missing table: " + table));
        actualTables.stream().filter(table -> !"flyway_schema_history".equals(table))
                .filter(table -> !expectedTables.contains(table))
                .forEach(table -> errors.add("unexpected table: " + table));

        for (Map.Entry<String, List<Column>> entry : EXPECTED_COLUMNS.entrySet()) {
            String table = entry.getKey();
            Map<String, Column> actualColumns = columns(connection, catalog, table);
            if (actualColumns.isEmpty()) {
                errors.add("missing table: " + table);
                continue;
            }
            for (Column expected : entry.getValue()) {
                Column actual = actualColumns.get(expected.name());
                if (actual == null) {
                    errors.add("missing column: " + table + "." + expected.name());
                } else {
                    verifyColumn(table, expected, actual, errors);
                }
            }
            Set<String> unexpected = new LinkedHashSet<>(actualColumns.keySet());
            entry.getValue().forEach(column -> unexpected.remove(column.name()));
            unexpected.forEach(column -> errors.add("unexpected column: " + table + "." + column));

            Map<String, String> indexes = indexes(metadata, catalog, table);
            for (Map.Entry<String, String> expected : EXPECTED_INDEXES.get(table).entrySet()) {
                String actual = indexes.get(expected.getKey());
                if (actual == null) {
                    errors.add("missing index: " + table + "." + expected.getKey());
                } else if (!expected.getValue().equals(actual)) {
                    errors.add("index mismatch: " + table + "." + expected.getKey()
                            + " expected " + expected.getValue() + " but was " + actual);
                }
            }

            Set<String> constraints = constraintNames(connection, catalog, table);
            missingNames(EXPECTED_CONSTRAINTS.get(table), constraints, "constraint", table, errors);

            Map<String, String> foreignKeys = foreignKeys(connection, catalog, table);
            EXPECTED_FOREIGN_KEYS.get(table).forEach((name, signature) -> {
                if (!signature.equals(foreignKeys.get(name))) {
                    errors.add("foreign key mismatch: " + table + "." + name
                            + " expected " + signature + " but was " + foreignKeys.get(name));
                }
            });

            verifyTableOptions(connection, catalog, table, errors);
        }
        verifyChecks(connection, catalog, errors);
        return errors;
    }

    private static Set<String> tableNames(Connection connection, String schema) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        String sql = """
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
        }
        return tables;
    }

    private static Map<String, Column> columns(Connection connection, String catalog, String table)
            throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        String sql = """
                SELECT COLUMN_NAME, DATA_TYPE,
                       CASE
                         WHEN DATA_TYPE = 'datetime' THEN 23
                         WHEN CHARACTER_MAXIMUM_LENGTH IS NOT NULL THEN CHARACTER_MAXIMUM_LENGTH
                         ELSE NUMERIC_PRECISION
                       END AS contract_size,
                       COALESCE(DATETIME_PRECISION, NUMERIC_SCALE, 0) AS contract_scale,
                       IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, catalog);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    columns.put(result.getString("COLUMN_NAME"), new Column(
                            result.getString("COLUMN_NAME"), result.getString("DATA_TYPE").toLowerCase(),
                            result.getInt("contract_size"), result.getInt("contract_scale"),
                            "YES".equals(result.getString("IS_NULLABLE")),
                            normalizedDefault(result.getString("COLUMN_DEFAULT")),
                            normalizedExtra(result.getString("EXTRA")).contains("auto_increment"),
                            onUpdateExpression(result.getString("EXTRA")),
                            normalizedExtra(result.getString("EXTRA"))));
                }
            }
        }
        return columns;
    }

    private static Map<String, String> indexes(DatabaseMetaData metadata, String catalog, String table)
            throws SQLException {
        Map<String, Map<Integer, String>> orderedColumns = new LinkedHashMap<>();
        try (ResultSet result = metadata.getIndexInfo(catalog, null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    orderedColumns.computeIfAbsent(name, ignored -> new java.util.TreeMap<>())
                            .put(result.getInt("ORDINAL_POSITION"), column);
                }
            }
        }
        Map<String, String> indexes = new LinkedHashMap<>();
        orderedColumns.forEach((name, columns) -> indexes.put(name, String.join(",", columns.values())));
        return indexes;
    }

    private static Set<String> constraintNames(Connection connection, String schema, String table)
            throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        String sql = """
                SELECT CONSTRAINT_NAME
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private static Map<String, String> foreignKeys(Connection connection, String schema, String table)
            throws SQLException {
        Map<String, String> keys = new LinkedHashMap<>();
        String sql = """
                SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    keys.put(result.getString(1), result.getString(2) + "->"
                            + result.getString(3) + "." + result.getString(4));
                }
            }
        }
        return keys;
    }

    private static void verifyTableOptions(Connection connection, String schema, String table,
                                           List<String> errors) throws SQLException {
        String sql = """
                SELECT ENGINE, TABLE_COLLATION FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && (!"InnoDB".equalsIgnoreCase(result.getString(1))
                        || !"utf8mb4_unicode_ci".equalsIgnoreCase(result.getString(2)))) {
                    errors.add("table options mismatch: " + table + " expected InnoDB/utf8mb4_unicode_ci"
                            + " but was " + result.getString(1) + "/" + result.getString(2));
                }
            }
        }
    }

    private static void verifyChecks(Connection connection, String schema, List<String> errors)
            throws SQLException {
        String sql = """
                SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE, tc.ENFORCED
                FROM information_schema.TABLE_CONSTRAINTS tc
                JOIN information_schema.CHECK_CONSTRAINTS cc
                  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                WHERE tc.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE = 'CHECK'
                """;
        Map<String, CheckConstraint> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), new CheckConstraint(
                            normalized(result.getString(2)), result.getString(3)));
                }
            }
        }
        EXPECTED_CHECKS.forEach((name, clause) -> {
            CheckConstraint constraint = actual.get(name);
            if (constraint == null || !clause.equals(constraint.clause())) {
                errors.add("check constraint mismatch: " + name + " expected " + clause
                        + " but was " + (constraint == null ? null : constraint.clause()));
            }
            if (constraint != null && !"YES".equalsIgnoreCase(constraint.enforced())) {
                errors.add("check constraint not enforced: " + name
                        + " expected ENFORCED=YES but was " + constraint.enforced());
            }
        });
    }

    private static void verifyColumn(String table, Column expected, Column actual,
                                     List<String> errors) {
        String qualified = table + "." + expected.name();
        if (!expected.coreMatches(actual)) {
            errors.add("column mismatch: " + qualified + " expected " + expected
                    + " but was " + actual);
        }
        if (expected.autoIncrement() != actual.autoIncrement()) {
            errors.add("column auto_increment mismatch: " + qualified + " expected "
                    + yesNo(expected.autoIncrement()) + " but was " + yesNo(actual.autoIncrement()));
        }
        if (!Objects.equals(expected.defaultValue(), actual.defaultValue())) {
            errors.add("column default mismatch: " + qualified + " expected "
                    + display(expected.defaultValue()) + " but was " + display(actual.defaultValue()));
        }
        if (!Objects.equals(expected.onUpdate(), actual.onUpdate())) {
            errors.add("column on-update mismatch: " + qualified + " expected "
                    + display(expected.onUpdate()) + " but was " + display(actual.onUpdate()));
        }
        if (!expected.extra().equals(actual.extra())) {
            errors.add("column extra mismatch: " + qualified + " expected "
                    + display(expected.extra()) + " but was " + display(actual.extra()));
        }
    }

    private static String normalizedDefault(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return "current_timestamp(3)".equalsIgnoreCase(trimmed)
                ? "current_timestamp(3)" : trimmed;
    }

    private static String normalizedExtra(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String onUpdateExpression(String extra) {
        String normalized = normalizedExtra(extra);
        String marker = "on update ";
        int index = normalized.indexOf(marker);
        return index < 0 ? null : normalizedDefault(normalized.substring(index + marker.length()));
    }

    private static String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }

    private static String display(String value) {
        return value == null ? "<none>" : value;
    }

    private static String normalized(String expression) {
        return expression.toLowerCase()
                .replace("`", "")
                .replace("\\", "")
                .replace("_utf8mb4", "")
                .replaceAll("[\\s()]", "");
    }

    private static void missingNames(Set<String> expected, Set<String> actual, String kind,
                                     String table, List<String> errors) {
        expected.stream()
                .filter(name -> !actual.contains(name))
                .forEach(name -> errors.add("missing " + kind + ": " + table + "." + name));
    }

    private static Map<String, List<Column>> expectedColumns() {
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        columns.put("users", List.of(
                id("id"), c("username", "varchar", 50, 0, false),
                c("password_hash", "varchar", 255, 0, false), c("full_name", "varchar", 100, 0, false),
                c("email", "varchar", 100, 0, false),
                c("role", "varchar", 20, 0, false, "ROLE_USER"),
                c("created_at", "datetime", 23, 3, false, "current_timestamp(3)")));
        columns.put("wallets", List.of(
                id("id"), c("user_id", "bigint", 19, 0, false),
                c("wallet_number", "varchar", 30, 0, false),
                c("balance", "bigint", 19, 0, false, "0"),
                c("status", "varchar", 20, 0, false, "ACTIVE"),
                c("version", "bigint", 19, 0, false, "0"),
                c("created_at", "datetime", 23, 3, false, "current_timestamp(3)"),
                c("updated_at", "datetime", 23, 3, false, "current_timestamp(3)",
                        "current_timestamp(3)")));
        columns.put("linked_bank_accounts", List.of(
                id("id"), c("wallet_id", "bigint", 19, 0, false),
                c("bank_code", "varchar", 20, 0, false), c("bank_name", "varchar", 100, 0, false),
                c("account_number_masked", "varchar", 50, 0, false),
                c("holder_name", "varchar", 100, 0, false),
                c("status", "varchar", 20, 0, false, "ACTIVE"),
                c("linked_at", "datetime", 23, 3, false, "current_timestamp(3)"),
                c("unlinked_at", "datetime", 23, 3, true)));
        columns.put("transactions", List.of(
                id("id"), c("reference_code", "varchar", 36, 0, false),
                c("type", "varchar", 20, 0, false), c("source_wallet_id", "bigint", 19, 0, true),
                c("target_wallet_id", "bigint", 19, 0, true),
                c("linked_bank_account_id", "bigint", 19, 0, true),
                c("amount", "bigint", 19, 0, false), c("description", "varchar", 255, 0, true),
                c("created_at", "datetime", 23, 3, false, "current_timestamp(3)")));
        return columns;
    }

    private static Column c(String name, String type, int size, int scale, boolean nullable) {
        return new Column(name, type, size, scale, nullable, null, false, null, "");
    }

    private static Column c(String name, String type, int size, int scale, boolean nullable,
                            String defaultValue) {
        String extra = "current_timestamp(3)".equals(defaultValue) ? "default_generated" : "";
        return new Column(name, type, size, scale, nullable, defaultValue, false, null, extra);
    }

    private static Column c(String name, String type, int size, int scale, boolean nullable,
                            String defaultValue, String onUpdate) {
        return new Column(name, type, size, scale, nullable, defaultValue, false, onUpdate,
                "default_generated on update " + onUpdate);
    }

    private static Column id(String name) {
        return new Column(name, "bigint", 19, 0, false, null, true, null, "auto_increment");
    }

    private record Column(String name, String type, int size, int scale, boolean nullable,
                          String defaultValue, boolean autoIncrement, String onUpdate, String extra) {
        boolean coreMatches(Column other) {
            return type.equals(other.type) && size == other.size && scale == other.scale
                    && nullable == other.nullable;
        }
    }

    private record CheckConstraint(String clause, String enforced) {
    }
}
