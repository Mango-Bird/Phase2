package sa.uet.digitalwallet_system.platform.observability;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class CorrelationIds {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private CorrelationIds() {
    }

    static String normalizeOrCreate(String candidate) {
        if (candidate == null || !UUID_PATTERN.matcher(candidate).matches()) {
            return create();
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    static boolean isValid(String candidate) {
        return candidate != null && UUID_PATTERN.matcher(candidate).matches();
    }

    static String create() {
        return UUID.randomUUID().toString();
    }
}
