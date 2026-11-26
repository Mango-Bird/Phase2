package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@PaymentDatabaseHttpTestSupport.InstanceTimeZone("America/New_York")
class PaymentHistoryDstIntegrationTest extends PaymentDatabaseHttpTestSupport {
    @BeforeEach void dstHistory() {
        // Legacy Hibernate UTC Timestamp read on New York displays 03:05 after the spring gap,
        // and displays 01:05 twice during the fall overlap. SQL fixtures are literal stored times.
        for(var row:List.of(new Object[]{1L,"2026-03-08 07:05:00.000"},
                new Object[]{2L,"2026-11-01 05:05:00.000"},new Object[]{3L,"2026-11-01 06:05:00.000"})) {
            jdbc.update("INSERT INTO transactions(id,reference_code,type,target_wallet_id,linked_bank_account_id,amount,created_at) VALUES (?,?,'TOPUP',11,31,1,?)",
                    row[0],"00000000-0000-0000-0000-%012d".formatted(row[0]),row[1]);
        }
    }
    HttpResponse<String> history(String query) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"+secondPort+"/api/v2/transactions"+query))
                .header("Authorization","Bearer "+token(101)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String range(String from,String to) { return "?from="+URLEncoder.encode(from,StandardCharsets.UTF_8)+"&to="+URLEncoder.encode(to,StandardCharsets.UTF_8); }
    @ParameterizedTest @ValueSource(strings={
            "from=2026-03-08T02:45:00&to=2026-03-08T03:15:00",
            "from=2026-03-08T01:55:00&to=2026-03-08T02:15:00",
            "from=2026-03-08T02:00:00",
            "to=2026-03-08T02:59:59.999",
            "from=2026-11-01T01:15:00&to=2026-11-01T02:15:00",
            "from=2026-11-01T00:55:00&to=2026-11-01T01:45:00"})
    void rejectsNonexistentOrAmbiguousBoundaryInsteadOfNormalizingIt(String query) throws Exception {
        var response=history("?"+query);
        assertThat(response.statusCode()).isEqualTo(400);
        var error=JSON.readTree(response.body());
        assertThat(error.path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(error.path("message").asText()).contains("daylight-saving");
        assertThat(error.path("retryable").asBoolean()).isFalse();
    }
    @Test void validBoundsAcrossSpringTransitionIncludeTheHistoryDisplayedAtThreeOhFive() throws Exception {
        var all=history(""); assertThat(all.statusCode()).isEqualTo(200);
        for(var item:JSON.readTree(all.body()).path("data").path("items")) if(item.path("id").asLong()==1)
            assertThat(LocalDateTime.parse(item.path("createdAt").asText())).isEqualTo(LocalDateTime.parse("2026-03-08T03:05:00"));
        for(String from:List.of("2026-03-08T01:55:00","2026-03-08T03:00:00")) {
            var response=history(range(from,"2026-03-08T03:15:00"));assertThat(response.statusCode()).isEqualTo(200);
            var items=JSON.readTree(response.body()).path("data").path("items");
            assertThat(items.size()).isEqualTo(1);assertThat(items.get(0).path("id").asLong()).isEqualTo(1);
        }
    }
    @Test void uniqueBoundsAcrossFallTransitionIncludeBothOccurrences() throws Exception {
        var response=history(range("2026-11-01T00:55:00","2026-11-01T02:15:00"));
        assertThat(response.statusCode()).isEqualTo(200);
        var items=JSON.readTree(response.body()).path("data").path("items");assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("id").asLong()).isEqualTo(3);assertThat(items.get(1).path("id").asLong()).isEqualTo(2);
        for(var item:items) assertThat(LocalDateTime.parse(item.path("createdAt").asText())).isEqualTo(LocalDateTime.parse("2026-11-01T01:05:00"));
    }
    @Override @AfterEach void everyCommittedPaymentHasExactlyOneMatchingEventAndNoOrphans() {
        // Read-only synthetic pre-C06 history; no business effect is expected.
        assertThat(count("transactions")).isEqualTo(3);assertThat(count("outbox_events")).isZero();assertThat(count("idempotency_requests")).isZero();
        assertThat(balance(11)).isEqualTo(1000);assertThat(balance(22)).isEqualTo(500);
    }
}
