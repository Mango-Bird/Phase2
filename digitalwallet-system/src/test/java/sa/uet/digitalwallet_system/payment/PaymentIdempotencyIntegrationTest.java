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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jwt.secret=c04-two-instance-test-only-signing-secret-0123456789")
class PaymentIdempotencyIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("c04_payments").withUsername("test_user").withPassword("test_password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired PaymentCommandApi api;
    @Autowired TransactionTemplate transaction;
    @MockitoSpyBean IdempotencyStore store;
    IdempotencyStore storeTarget;
    static Process second;
    static int secondLockWait;
    static long secondPid;
    static int secondPort;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOPUP = "{\"linkedBankAccountId\":31,\"amount\":100}";

    static void startSecond() throws Exception {
        Path directory = Path.of("build/c04-process-test").toAbsolutePath();
        Files.createDirectories(directory);
        Path probe = Files.createTempFile(directory, "instance-", ".probe");
        Path log = probe.resolveSibling(probe.getFileName() + ".log");
        Path arguments = probe.resolveSibling(probe.getFileName() + ".args");
        // Give the child only production dependencies and this one launcher; no MVC test configurations.
        String resource = "sa/uet/digitalwallet_system/payment/support/IndependentPaymentInstance.class";
        Path launchRoot = directory.resolve("launcher");
        Path launchClass = launchRoot.resolve(resource);
        Files.createDirectories(launchClass.getParent());
        try (var input = PaymentIdempotencyIntegrationTest.class.getResourceAsStream("/"+resource)) {
            Files.copy(java.util.Objects.requireNonNull(input), launchClass, StandardCopyOption.REPLACE_EXISTING);
        }
        String classpath = launchRoot + java.io.File.pathSeparator + System.getProperty("c04.production.classpath");
        var args = List.of("-cp", classpath,
                "sa.uet.digitalwallet_system.payment.support.IndependentPaymentInstance", probe.toString(),
                "--server.port=0", "--spring.datasource.url="+MYSQL.getJdbcUrl(),
                "--spring.datasource.username="+MYSQL.getUsername(), "--spring.datasource.password="+MYSQL.getPassword(),
                "--app.jwt.secret=c04-two-instance-test-only-signing-secret-0123456789", "--spring.main.banner-mode=off");
        var lines = new ArrayList<String>();
        for (String arg : args) lines.add(quoteArgument(arg));
        Files.write(arguments, lines, StandardCharsets.UTF_8);
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        second = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx256m", "@"+arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (second.isAlive() && Files.size(probe) == 0 && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(Files.size(probe)).withFailMessage("Independent JVM did not start: %s", Files.readString(log)).isPositive();
        var values = Files.readAllLines(probe);
        secondPort = Integer.parseInt(values.get(0)); secondLockWait = Integer.parseInt(values.get(1));
        secondPid = Long.parseLong(values.get(2));
        assertThat(secondPid).isNotEqualTo(ProcessHandle.current().pid());
    }
    static String quoteArgument(String arg) {
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    @BeforeAll static void anotherIndependentApplicationInstance() throws Exception { startSecond(); }
    @AfterAll static void shutdownSecond() throws Exception {
        if (second != null && second.isAlive()) {
            second.destroy();
            if (!second.waitFor(10,TimeUnit.SECONDS)) { second.destroyForcibly(); second.waitFor(10,TimeUnit.SECONDS); }
        }
    }
    @BeforeEach void seed() {
        storeTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(store);
        jdbc.update("DELETE FROM idempotency_requests"); jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM linked_bank_accounts"); jdbc.update("DELETE FROM wallets"); jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,username,password_hash,full_name,email,role,created_at) VALUES "
                +"(101,'source','hash','Source','source@example.com','ROLE_USER',UTC_TIMESTAMP()),"
                +"(202,'target','hash','Target','target@example.com','ROLE_USER',UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO wallets(id,user_id,wallet_number,balance,status,version,created_at,updated_at) VALUES "
                +"(11,101,'SOURCE',1000,'ACTIVE',0,UTC_TIMESTAMP(),UTC_TIMESTAMP()),"
                +"(22,202,'TARGET',500,'ACTIVE',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO linked_bank_accounts(id,wallet_id,bank_code,bank_name,account_number_masked,holder_name,status,linked_at) VALUES "
                +"(31,11,'BANK','Bank','******1234','Source','ACTIVE',UTC_TIMESTAMP()),"
                +"(32,22,'BANK','Bank','******5678','Target','ACTIVE',UTC_TIMESTAMP())");
    }
    String token(long actor) { return tokens.issue(actor, "test-user", "ROLE_USER").value(); }
    HttpRequest request(int targetPort, long actor, String op, String key, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+targetPort+"/api/v1/transactions/"+op))
                .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer "+token(actor))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> send(int targetPort, long actor, String op, String key, String body) throws Exception {
        return HTTP.send(request(targetPort,actor,op,key,body), HttpResponse.BodyHandlers.ofString());
    }
    long balance(long id) { return jdbc.queryForObject("SELECT balance FROM wallets WHERE id=?",Long.class,id); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    void oneEffect(long expectedBalance) {
        assertThat(count("transactions")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(balance(11)).isEqualTo(expectedBalance);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_requests WHERE status='PROCESSING'",Long.class)).isZero();
    }

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

    @Test void publicFacadeRejectsCallerTransactionAndCreationOfDurableProcessingRowsIsNotUsed() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> api.topup(101,"nested",new TopupInput(31,100,null))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("idempotency_requests")).isZero();
    }
}
