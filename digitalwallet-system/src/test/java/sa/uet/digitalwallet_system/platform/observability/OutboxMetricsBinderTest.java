package sa.uet.digitalwallet_system.platform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsBinderTest {
    @Test
    void publishesExactBoundedStatusAndAgeMetricsFromDatabaseValues() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("PENDING"))).thenReturn(3L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("CLAIMED"))).thenReturn(2L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("DEAD"))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(12.5);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetricsBinder(jdbc).bindTo(registry);

        assertThat(registry.get("digitalwallet.outbox.events").tag("status", "pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("digitalwallet.outbox.events").tag("status", "claimed").gauge().value()).isEqualTo(2);
        assertThat(registry.get("digitalwallet.outbox.events").tag("status", "dead").gauge().value()).isEqualTo(1);
        assertThat(registry.get("digitalwallet.outbox.oldest.pending.age").gauge().value()).isEqualTo(12.5);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("status")));
    }

    @Test
    void databaseFailureIsNanAndNeverFabricatedAsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        when(jdbc.queryForObject(anyString(), eq(Double.class)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetricsBinder(jdbc).bindTo(registry);

        assertThat(registry.get("digitalwallet.outbox.events").tag("status", "pending").gauge().value()).isNaN();
        assertThat(registry.get("digitalwallet.outbox.oldest.pending.age").gauge().value()).isNaN();
    }
}
