package sa.uet.digitalwallet_system.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.port.out.IdempotencyStore;
import sa.uet.digitalwallet_system.platform.security.JwtTokenService;

import java.net.*;
import java.nio.file.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentIdempotencyIntegrationTest extends PaymentDatabaseHttpTestSupport {
    @Test void oneHundredDuplicateRequestsAcrossTwoInstancesHaveOneEffect() throws Exception {
        var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i=0;i<100;i++) requests.add(HTTP.sendAsync(request(i%2==0?port:secondPort,101,"topup","burst",TOPUP),HttpResponse.BodyHandlers.ofString()));
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(60,TimeUnit.SECONDS);
        Set<String> successes = new HashSet<>();
        for(var future:requests) {
            var response=future.get();
            assertThat(response.statusCode()).isIn(200,409);
            if(response.statusCode()==200) successes.add(response.body());
            else assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("REQUEST_IN_PROGRESS");
        }
        var replay=send(secondPort,101,"topup","burst",TOPUP);
        assertThat(replay.statusCode()).isEqualTo(200); successes.add(replay.body());
        assertThat(successes).hasSize(1); oneEffect(1100);
    }

    @Test void replayIsExactAndIgnoresLaterBankAndWalletState() throws Exception {
        var original=send(port,101,"topup","snapshot",TOPUP); assertThat(original.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE linked_bank_accounts SET status='UNLINKED',unlinked_at=UTC_TIMESTAMP() WHERE id=31");
        jdbc.update("UPDATE wallets SET status='FROZEN',version=version+1 WHERE id=11");
        var replay=send(secondPort,101,"topup","snapshot","{\"description\":null,\"amount\":100,\"linkedBankAccountId\":31}");
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).isEqualTo(original.body()); oneEffect(1100);
    }

    @Test void committedKeyRejectsPayloadMismatchIncludingEmptyVersusNull() throws Exception {
        assertThat(send(port,101,"topup","mismatch",TOPUP).statusCode()).isEqualTo(200);
        for(String body:List.of("{\"linkedBankAccountId\":31,\"amount\":101}","{\"linkedBankAccountId\":31,\"amount\":100,\"description\":\"\"}")) {
            var conflict=send(secondPort,101,"topup","mismatch",body);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(JSON.readTree(conflict.body()).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
        oneEffect(1100);
    }

    @Test void actorOperationCaseAndDifferentKeysAreIndependent() throws Exception {
        assertThat(send(port,101,"topup","shared",TOPUP).statusCode()).isEqualTo(200);
        assertThat(send(secondPort,202,"topup","shared","{\"linkedBankAccountId\":32,\"amount\":100}").statusCode()).isEqualTo(200);
        assertThat(send(port,101,"withdraw","shared",TOPUP).statusCode()).isEqualTo(200);
        assertThat(send(secondPort,101,"topup","SHARED",TOPUP).statusCode()).isEqualTo(200);
        assertThat(send(port,101,"topup","another-key",TOPUP).statusCode()).isEqualTo(200);
        assertThat(count("transactions")).isEqualTo(5); assertThat(count("idempotency_requests")).isEqualTo(5);
        assertThat(balance(11)).isEqualTo(1200); assertThat(balance(22)).isEqualTo(600);
    }

    @Test void transferReplayDoesNotDebitOrCreditAgain() throws Exception {
        String body="{\"targetWalletNumber\":\"TARGET\",\"amount\":250,\"description\":\"Chuyển tiền\"}";
        var first=send(port,101,"transfer","transfer",body); assertThat(first.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).path("data").path("description").asText()).isEqualTo("Chuyển tiền");
        var replay=send(secondPort,101,"transfer","transfer",body);
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).isEqualTo(first.body());
        oneEffect(750); assertThat(balance(22)).isEqualTo(750);
    }

    @Test void failureAfterCompletedResultWriteRollsBackKeyMoneyAndHistory() throws Exception {
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("injected after completed write"); })
                .when(storeTarget).complete(eq(101L),eq(PaymentOperation.TOPUP),eq("rollback"),anyLong(),anyString(),anyString());
        assertThat(send(port,101,"topup","rollback",TOPUP).statusCode()).isEqualTo(500);
        assertThat(count("idempotency_requests")).isZero(); assertThat(count("transactions")).isZero(); assertThat(balance(11)).isEqualTo(1000);
        reset(storeTarget);
        var retry=send(port,101,"topup","rollback","{\"linkedBankAccountId\":31,\"amount\":200}");
        assertThat(retry.statusCode()).isEqualTo(200); oneEffect(1200);
    }

    @Test void businessFailureDoesNotCacheKeyAndLaterRequestMaySucceed() throws Exception {
        assertThat(send(port,101,"withdraw","insufficient","{\"linkedBankAccountId\":31,\"amount\":2000}").statusCode()).isEqualTo(400);
        assertThat(count("idempotency_requests")).isZero(); assertThat(count("transactions")).isZero();
        assertThat(send(secondPort,101,"withdraw","insufficient",TOPUP).statusCode()).isEqualTo(200); oneEffect(900);
    }

    @Test void claimLockWaitIsBoundedAndReturnsRetryableInProgress() throws Exception {
        assertThat(jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout",Integer.class)).isEqualTo(2);
        assertThat(secondLockWait).isEqualTo(2);
        var acquired=new CountDownLatch(1); var release=new CountDownLatch(1);
        var holder=CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            store.claim(101,PaymentOperation.TOPUP,"held","a".repeat(64)); acquired.countDown();
            try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
            status.setRollbackOnly();
        }));
        try {
            assertThat(acquired.await(10,TimeUnit.SECONDS)).isTrue();
            var blocked=send(secondPort,101,"topup","held",TOPUP);
            assertThat(blocked.statusCode()).isEqualTo(409);
            assertThat(JSON.readTree(blocked.body()).get("code").asText()).isEqualTo("REQUEST_IN_PROGRESS");
            assertThat(JSON.readTree(blocked.body()).get("retryable").asBoolean()).isTrue();
            assertThat(blocked.headers().firstValue("Retry-After")).contains("1");
        } finally { release.countDown(); holder.get(20,TimeUnit.SECONDS); }
        assertThat(count("idempotency_requests")).isZero(); assertThat(count("transactions")).isZero();
        assertThat(send(port,101,"topup","held",TOPUP).statusCode()).isEqualTo(200); oneEffect(1100);
    }

    @Test void lostResponseThenInstanceRestartReplaysDurableSnapshot() throws Exception {
        byte[] body=TOPUP.getBytes(StandardCharsets.UTF_8);
        try(var socket=new Socket("localhost",secondPort)) {
            String head="POST /api/v1/transactions/topup HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token(101)
                    +"\r\nIdempotency-Key: lost-response\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body); socket.getOutputStream().flush();
            // Client closes without reading the response. Commit must be recovered from durable storage.
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(count("idempotency_requests")==0 && System.nanoTime()<deadline) Thread.sleep(25);
        oneEffect(1100);
        String saved=jdbc.queryForObject("SELECT response_body FROM idempotency_requests WHERE idempotency_key='lost-response'",String.class);
        long oldPid = secondPid;
        second.destroyForcibly(); assertThat(second.waitFor(10,TimeUnit.SECONDS)).isTrue();
        startSecond(); assertThat(secondPid).isNotEqualTo(oldPid);
        var replay=send(secondPort,101,"topup","lost-response",TOPUP);
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).isEqualTo(saved); oneEffect(1100);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"topup", "withdraw", "transfer"})
    void chunkedRequestWithoutContentLengthIsLimitedBeforeAnyWrites(String operation) throws Exception {
        String field = operation.equals("transfer") ? "\"targetWalletNumber\":\"TARGET\"" : "\"linkedBankAccountId\":31";
        byte[] body = (" ".repeat(16*1024)+"{"+field+",\"amount\":100}").getBytes(StandardCharsets.UTF_8);
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body));
        assertThat(publisher.contentLength()).isEqualTo(-1);
        int targetPort = operation.equals("withdraw") ? secondPort : port;
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"+targetPort+"/api/v1/transactions/"+operation))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer "+token(101)).header("Idempotency-Key", "chunked-limit")
                .header("Content-Type", "application/json").POST(publisher).build();
        assertThat(request.headers().firstValue("Content-Length")).isEmpty();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        var error = JSON.readTree(response.body());
        assertThat(error.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(error.get("retryable").asBoolean()).isFalse();
        assertThat(error.get("traceId").asText()).isNotBlank();
        assertThat(count("idempotency_requests")).isZero(); assertThat(count("transactions")).isZero();
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(500);
    }

    @Test void runtimeOpenApiRequiresKeyAndDescribesStoredJsonAsObject() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var document = JSON.readTree(response.body());
        for (String operation : List.of("topup", "withdraw", "transfer")) {
            var post = document.path("paths").path("/api/v1/transactions/"+operation).path("post");
            com.fasterxml.jackson.databind.JsonNode key = null;
            for (var parameter : post.path("parameters")) if (parameter.path("name").asText().equals("Idempotency-Key")) key = parameter;
            assertThat(key).as(operation+" key parameter").isNotNull();
            assertThat(key.path("in").asText()).isEqualTo("header");
            assertThat(key.path("required").asBoolean()).isTrue();
            assertThat(key.path("schema").path("maxLength").asInt()).isEqualTo(64);
            assertThat(post.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText())
                    .endsWith("/PaymentSuccessResponse");
            assertThat(post.path("responses").path("413").path("content").path("application/json").path("schema").path("$ref").asText())
                    .endsWith("/ErrorResponse");
        }
    }

    @Test void publicFacadeRejectsCallerTransactionAndCreationOfDurableProcessingRowsIsNotUsed() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> api.topup(101,"nested",new TopupInput(31,100,null))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("idempotency_requests")).isZero();
    }
}
