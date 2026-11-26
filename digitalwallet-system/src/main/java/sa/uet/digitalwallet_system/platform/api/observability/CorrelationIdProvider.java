package sa.uet.digitalwallet_system.platform.api.observability;

import java.util.Optional;

/** Public platform seam for request and persisted-event correlation. */
public interface CorrelationIdProvider {
    Optional<String> current();

    String currentOrCreate();

    Scope install(String correlationId);

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
