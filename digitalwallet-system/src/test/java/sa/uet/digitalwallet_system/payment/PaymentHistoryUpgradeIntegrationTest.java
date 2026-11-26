package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class PaymentHistoryUpgradeIntegrationTest extends PaymentDatabaseHttpTestSupport {
    long legacyId;

    List<String> installLegacyFixture() throws Exception {
        Path dir=Path.of("build/c06-legacy-upgrade").toAbsolutePath(); Files.createDirectories(dir);
        Path root=dir.resolve("launcher");
        String base="sa/uet/digitalwallet_system/payment/support/LegacyPaymentFixture";
        for(String suffix:List.of(".class","$LegacyTransaction.class")) {
            Path destination=root.resolve(base+suffix); Files.createDirectories(destination.getParent());
            try(var input=getClass().getResourceAsStream("/"+base+suffix)) {
                Files.copy(Objects.requireNonNull(input),destination,StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path result=Files.createTempFile(dir,"fixture-",".result");
        Path log=result.resolveSibling(result.getFileName()+".log");
        Path arguments=result.resolveSibling(result.getFileName()+".args");
        var args=List.of("-cp",root+java.io.File.pathSeparator+System.getProperty("c04.production.classpath"),
                base.replace('/','.'),result.toString(),MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Files.write(arguments,args.stream().map(PaymentDatabaseHttpTestSupport::quoteArgument).toList());
        String executable=System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Xmx192m","-Duser.timezone=Asia/Bangkok","@"+arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(45,TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage("Legacy fixture failed: %s",Files.readString(log)).isZero();
            return Files.readAllLines(result);
        } finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); } }
    }
    HttpResponse<String> history() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"+secondPort+"/api/v1/transactions"))
                .header("Authorization","Bearer "+token(101)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String createdAt(long id) throws Exception {
        var response=history(); assertThat(response.statusCode()).isEqualTo(200);
        for(var row:JSON.readTree(response.body()).path("data")) if(row.path("id").asLong()==id) return row.path("createdAt").asText();
        throw new AssertionError("missing history "+id);
    }
    @Test void upgradePreservesUtcPlusSevenLegacyHistoryReceiptAndDoesNotBackfillEvents() throws Exception {
        var fixture=installLegacyFixture(); legacyId=Long.parseLong(fixture.get(0));
        assertThat(LocalDateTime.parse(fixture.get(1))).isEqualTo(LocalDateTime.parse("2026-11-20T10:15:30.123"));
        assertThat(LocalDateTime.parse(fixture.get(2))).isEqualTo(LocalDateTime.parse("2026-11-20T03:15:30.123"));
        String saved=jdbc.queryForObject("SELECT response_body FROM idempotency_requests WHERE idempotency_key='legacy-upgrade'",String.class);
        String originalReceiptTime=JSON.readTree(saved).path("data").path("createdAt").asText();
        assertThat(LocalDateTime.parse(createdAt(legacyId))).isEqualTo(LocalDateTime.parse(fixture.get(1)));
        assertThat(LocalDateTime.parse(originalReceiptTime)).isEqualTo(LocalDateTime.parse(fixture.get(1)));
        var replay=send(secondPort,101,"transfer","legacy-upgrade","{\"targetWalletNumber\":\"TARGET\",\"amount\":100}");
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).isEqualTo(saved);
        assertThat(count("outbox_events")).isZero(); assertThat(balance(11)).isEqualTo(900); assertThat(balance(22)).isEqualTo(600);
        var fresh=send(secondPort,101,"transfer","after-upgrade","{\"targetWalletNumber\":\"TARGET\",\"amount\":100}");
        assertThat(fresh.statusCode()).isEqualTo(200);
        var data=JSON.readTree(fresh.body()).path("data"); long freshId=data.path("id").asLong();
        assertThat(LocalDateTime.parse(createdAt(freshId))).isEqualTo(LocalDateTime.parse(data.path("createdAt").asText()));
        var event=JSON.readTree(jdbc.queryForObject("SELECT payload FROM outbox_events",String.class));
        assertThat(event.path("transactionId").asLong()).isEqualTo(freshId);
        assertThat(Instant.parse(event.path("occurredAt").asText())).isEqualTo(LocalDateTime.parse(data.path("createdAt").asText()).toInstant(ZoneOffset.UTC));
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') FROM transactions WHERE id=?",String.class,legacyId)).isEqualTo(fixture.get(2));
        assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency_requests WHERE idempotency_key='legacy-upgrade'",String.class)).isEqualTo(saved);
        assertThat(LocalDateTime.parse(createdAt(legacyId))).isEqualTo(LocalDateTime.parse(fixture.get(1)));
    }
    // Pre-C06 history legitimately has no outbox. Only the new payment may have one; do not backfill old events.
    @Override @AfterEach void everyCommittedPaymentHasExactlyOneMatchingEventAndNoOrphans() {
        assertThat(count("transactions")).isEqualTo(2); assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=?",Long.class,legacyId)).isZero();
        assertThat(balance(11)).isEqualTo(800); assertThat(balance(22)).isEqualTo(700);
    }
}
