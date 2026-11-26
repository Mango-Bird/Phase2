package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.OptimisticLockingFailureException;
import sa.uet.digitalwallet_system.platform.api.events.PaymentCompletedV1;
import sa.uet.digitalwallet_system.platform.api.outbox.OutboxAppendPort;

import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentOutboxIntegrationTest extends PaymentDatabaseHttpTestSupport {
    @MockitoSpyBean OutboxAppendPort outbox;

    String body(String operation) {
        return operation.equals("transfer") ? "{\"targetWalletNumber\":\"TARGET\",\"amount\":100,\"description\":\"private description\"}" : TOPUP;
    }
    OutboxAppendPort target() { return AopTestUtils.getUltimateTargetObject(outbox); }
    void noEffects() {
        assertThat(count("outbox_events")).isZero();
        assertThat(count("transactions")).isZero();
        assertThat(count("idempotency_requests")).isZero();
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT SUM(version) FROM wallets",Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"topup","withdraw","transfer"})
    void committedPayloadMatchesHistoryVerifiedRecipientsAndCorrelationAndReplayIsReadOnly(String operation) throws Exception {
        String correlationId=java.util.UUID.randomUUID().toString();
        // Child JVM is explicitly UTC+7, so UTC CI also detects accidental Timestamp timezone shifts.
        var request=java.net.http.HttpRequest.newBuilder(request(secondPort,101,operation,"payload",body(operation)).uri())
                .header("Authorization","Bearer "+token(101)).header("Content-Type","application/json")
                .header("Idempotency-Key","payload").header("X-Correlation-ID",correlationId)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body(operation))).build();
        var first=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events",String.class);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var event=mapper.readValue(payload,PaymentCompletedV1.class);
        var tx=jdbc.queryForMap("SELECT * FROM transactions");
        assertThat(event.transactionId()).isEqualTo((Long)tx.get("id"));
        assertThat(event.referenceCode().toString()).isEqualTo(tx.get("reference_code"));
        assertThat(event.eventId()).isNotEqualTo(event.referenceCode());
        assertThat(event.type().name()).isEqualTo(operation.toUpperCase(java.util.Locale.ROOT));
        assertThat(event.amount()).isEqualTo(100); assertThat(event.currency()).isEqualTo("VND");
        assertThat(event.eventType()).isEqualTo("payment.completed.v1"); assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.sourceWalletId()).isEqualTo(operation.equals("topup")?null:11L);
        assertThat(event.sourceUserId()).isEqualTo(operation.equals("topup")?null:101L);
        assertThat(event.targetWalletId()).isEqualTo(operation.equals("withdraw")?null:operation.equals("topup")?11L:22L);
        assertThat(event.targetUserId()).isEqualTo(operation.equals("withdraw")?null:operation.equals("topup")?101L:202L);
        // Read DATETIME literally: JdbcTemplate's generic Timestamp conversion applies the JVM zone.
        var historyInstant=java.time.LocalDateTime.parse(jdbc.queryForObject(
                "SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') FROM transactions",String.class)).toInstant(ZoneOffset.UTC);
        assertThat(event.occurredAt()).isEqualTo(historyInstant);
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(first.headers().firstValue("X-Correlation-ID")).contains(correlationId);
        assertThat(payload).doesNotContain("private description","accountNumber","password","token","idempotency");
        var row=jdbc.queryForMap("SELECT * FROM outbox_events");
        assertThat(row).containsEntry("status","PENDING").containsEntry("attempts",0).containsEntry("aggregate_id",event.transactionId());
        assertThat(row.get("claim_token")).isNull(); assertThat(row.get("processed_at")).isNull();
        var replay=send(port,101,operation,"payload",body(operation));
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_events",String.class)).isEqualTo(payload);
        oneEffect(operation.equals("topup")?1100:900);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> faults() {
        return Stream.of("topup","withdraw","transfer").flatMap(op ->
                Stream.of("BEFORE_APPEND","AFTER_APPEND","AFTER_COMPLETION","BEFORE_COMMIT")
                        .map(point -> org.junit.jupiter.params.provider.Arguments.of(op,point)));
    }
    @ParameterizedTest @MethodSource("faults")
    void everyFailureRollsBackMoneyHistoryKeyAndOutbox(String operation,String point) throws Exception {
        var reached=new AtomicInteger();
        if(point.equals("BEFORE_APPEND")||point.equals("AFTER_APPEND")) {
            doAnswer(call -> {
                reached.incrementAndGet();
                assertThat(count("transactions")).isEqualTo(1);
                if(point.equals("AFTER_APPEND")) { call.callRealMethod(); assertThat(count("outbox_events")).isEqualTo(1); }
                throw new IllegalStateException("C06 fault "+point);
            }).when(target()).append(any());
        } else {
            doAnswer(call -> {
                call.callRealMethod();
                assertThat(count("outbox_events")).isEqualTo(1);
                if(point.equals("AFTER_COMPLETION")) { reached.incrementAndGet(); throw new IllegalStateException("C06 fault completed"); }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        reached.incrementAndGet(); assertThat(count("outbox_events")).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_requests",String.class)).isEqualTo("COMPLETED");
                        throw new IllegalStateException("C06 fault commit");
                    }
                });
                return null;
            }).when(storeTarget).complete(anyLong(),any(),anyString(),anyLong(),anyString(),anyString());
        }
        assertThat(send(port,101,operation,"fault",body(operation)).statusCode()).isEqualTo(500);
        assertThat(reached.get()).isEqualTo(1); noEffects();
        reset(target(),storeTarget);
        assertThat(send(port,101,operation,"fault",body(operation)).statusCode()).isEqualTo(200);
        oneEffect(operation.equals("topup")?1100:900);
    }

    @Test void realOutboxConstraintFailureRollsBackTheEntirePayment() throws Exception {
        doAnswer(call -> { call.callRealMethod(); return call.callRealMethod(); }).when(target()).append(any());
        assertThat(send(port,101,"transfer","db-duplicate",body("transfer")).statusCode()).isEqualTo(500);
        verify(target(),times(1)).append(any()); noEffects();
    }

    @Test void retryAfterInsertedEventRollsBackOldEventAndCommitsExactlyOneNewEvent() throws Exception {
        var attempts=new AtomicInteger(); var eventIds=new java.util.ArrayList<java.util.UUID>();
        doAnswer(call -> {
            call.callRealMethod(); eventIds.add(((PaymentCompletedV1)call.getArgument(0)).eventId());
            assertThat(count("outbox_events")).isEqualTo(1);
            if(attempts.incrementAndGet()==1) throw new OptimisticLockingFailureException("C06 after append optimistic fault");
            return null;
        }).when(target()).append(any());
        assertThat(send(port,101,"topup","retry",TOPUP).statusCode()).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(2); assertThat(eventIds).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_events",String.class)).isEqualTo(eventIds.get(1).toString());
        oneEffect(1100);
        assertThat(jdbc.queryForObject("SELECT version FROM wallets WHERE id=11",Long.class)).isEqualTo(1);
    }
}
