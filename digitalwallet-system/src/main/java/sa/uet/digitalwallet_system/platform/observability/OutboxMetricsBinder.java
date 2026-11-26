package sa.uet.digitalwallet_system.platform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public final class OutboxMetricsBinder implements MeterBinder {
    private final OutboxMetricsCollector collector;

    public OutboxMetricsBinder(OutboxMetricsCollector collector) {
        this.collector = collector;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        bindStatus(registry, OutboxMetric.PENDING, "pending");
        bindStatus(registry, OutboxMetric.CLAIMED, "claimed");
        bindStatus(registry, OutboxMetric.DEAD, "dead");
        Gauge.builder("digitalwallet.outbox.oldest.pending.age", collector,
                        value -> value.value(OutboxMetric.OLDEST_PENDING_AGE))
                .description("Age of the oldest pending outbox event")
                .baseUnit("seconds")
                .register(registry);
    }

    private void bindStatus(MeterRegistry registry, OutboxMetric metric, String status) {
        Gauge.builder("digitalwallet.outbox.events", collector, value -> value.value(metric))
                .description("Current outbox event count by actionable status")
                .baseUnit("events")
                .tag("status", status)
                .register(registry);
    }
}
