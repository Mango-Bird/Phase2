package sa.uet.digitalwallet_system.platform.observability;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.platform.api.observability.CorrelationIdProvider;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class MdcCorrelationIdProvider implements CorrelationIdProvider {
    public static final String CORRELATION_KEY = "correlationId";
    public static final String TRACE_KEY = "traceId";

    @Override
    public Optional<String> current() {
        return Optional.ofNullable(MDC.get(CORRELATION_KEY));
    }

    @Override
    public String currentOrCreate() {
        return current().orElseGet(CorrelationIds::create);
    }

    @Override
    public Scope install(String correlationId) {
        String effective = CorrelationIds.normalizeOrCreate(correlationId);
        String previousCorrelation = MDC.get(CORRELATION_KEY);
        String previousTrace = MDC.get(TRACE_KEY);
        MDC.put(CORRELATION_KEY, effective);
        MDC.put(TRACE_KEY, effective);
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (!open.compareAndSet(true, false)) return;
            restore(CORRELATION_KEY, previousCorrelation);
            restore(TRACE_KEY, previousTrace);
        };
    }

    private static void restore(String key, String previous) {
        if (previous == null) MDC.remove(key);
        else MDC.put(key, previous);
    }
}
