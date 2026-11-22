package sa.uet.digitalwallet_system.platform.api.error;

import java.sql.SQLException;
import java.util.*;

/** MySQL-specific codes, including nested JDBC batch exceptions. No broad transient-error retries. */
public final class SqlFailures {
    private SqlFailures() {}
    public static boolean isMySqlDeadlock(Throwable failure) { return matches(failure, 1213, Set.of("40001")); }
    public static boolean isMySqlLockTimeout(Throwable failure) { return matches(failure, 1205, Set.of("HY000", "41000", "40001")); }
    private static boolean matches(Throwable failure, int code, Set<String> states) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>(); pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.remove();
            if (!seen.add(current)) continue;
            if (current instanceof SQLException sql) {
                if (sql.getErrorCode() == code && sql.getSQLState() != null && states.contains(sql.getSQLState())) return true;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (current.getCause() != null) pending.add(current.getCause());
        }
        return false;
    }
}
