package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PaymentHistoryQueryIntegrationTest extends PaymentDatabaseHttpTestSupport {
    String ref(long id) { return "00000000-0000-0000-0000-%012d".formatted(id); }
    void insert(long id,String type,String time) {
        boolean foreign=type.equals("FOREIGN"); String actual=foreign?"WITHDRAW":type;
        Long source=actual.equals("TOPUP")?null:foreign?22L:11L;
        Long target=actual.equals("WITHDRAW")?null:actual.equals("TOPUP")?11L:22L;
        Long bank=actual.equals("TRANSFER")?null:foreign?32L:31L;
        jdbc.update("INSERT INTO transactions(id,reference_code,type,source_wallet_id,target_wallet_id,linked_bank_account_id,amount,created_at) VALUES (?,?,?,?,?,?,1,?)",id,ref(id),actual,source,target,bank,time);
    }
    @BeforeEach void historyFixture() {
        for(int i=1;i<=53;i++) insert(i,i%3==0?"TOPUP":i%3==1?"TRANSFER":"WITHDRAW","2026-11-20 10:00:00.123");
        insert(100,"FOREIGN","2026-11-21 10:00:00.123");
    }
    HttpResponse<String> get(int targetPort,long actor,String path) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+targetPort+path)).GET();
        if(actor!=0) builder.header("Authorization","Bearer "+token(actor));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String enc(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    com.fasterxml.jackson.databind.JsonNode page(String query) throws Exception {
        var response=get(secondPort,101,"/api/v2/transactions"+query); assertThat(response.statusCode()).isEqualTo(200); return JSON.readTree(response.body()).path("data");
    }
    @Test void fixedTimestampTiesTraverseOnceAndLimitCanChangeAcrossInstances() throws Exception {
        var ids=new ArrayList<Long>(); String cursor=null; int i=0;
        do {
            String q="?limit="+(i%2==0?7:11)+(cursor==null?"":"&cursor="+enc(cursor));
            var response=get(i++%2==0?port:secondPort,101,"/api/v2/transactions"+q);
            assertThat(response.statusCode()).isEqualTo(200); var data=JSON.readTree(response.body()).path("data");
            for(var item:data.path("items")) ids.add(item.path("id").asLong());
            cursor=data.path("nextCursor").isNull()?null:data.path("nextCursor").asText();
            assertThat(data.path("hasMore").asBoolean()).isEqualTo(cursor!=null);
            assertThat(i).isLessThan(20);
        } while(cursor!=null);
        assertThat(ids).containsExactlyElementsOf(java.util.stream.LongStream.iterate(53,n -> n-1).limit(53).boxed().toList());
        assertThat(page("").path("items").size()).isEqualTo(20);
        assertThat(page("?limit=100").path("items").size()).isEqualTo(53);
    }
    @Test void ownershipForDetailAndCursorAndLegacyListIsPreserved() throws Exception {
        assertThat(get(port,101,"/api/v2/transactions/"+ref(1)).statusCode()).isEqualTo(200);
        assertThat(get(port,202,"/api/v2/transactions/"+ref(1)).statusCode()).isEqualTo(200);
        var recipientHistory=get(port,202,"/api/v2/transactions?limit=100");
        assertThat(recipientHistory.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(recipientHistory.body()).path("data").path("items").size()).isEqualTo(19);
        for(long id:List.of(100L,999L)) {
            var response=get(port,101,"/api/v2/transactions/"+ref(id)); assertThat(response.statusCode()).isEqualTo(404);
            assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("PAYMENT_NOT_FOUND");
        }
        assertThat(get(port,0,"/api/v2/transactions").statusCode()).isEqualTo(401);
        assertThat(get(port,0,"/api/v2/transactions/"+ref(1)).statusCode()).isEqualTo(401);
        String cursor=page("?limit=2").path("nextCursor").asText();
        assertThat(get(port,202,"/api/v2/transactions?cursor="+enc(cursor)).statusCode()).isEqualTo(400);
        assertThat(get(port,101,"/api/v2/transactions?type=TOPUP&cursor="+enc(cursor)).statusCode()).isEqualTo(400);
        var legacy=get(secondPort,101,"/api/v1/transactions"); assertThat(legacy.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(legacy.body()).path("data").isArray()).isTrue();
        assertThat(JSON.readTree(legacy.body()).path("data").size()).isEqualTo(53);
    }
    @Test void maxPageIsBoundedWithMoreThanOneHundredOwnedRows() throws Exception {
        for(int i=201;i<=400;i++) insert(i,"TOPUP","2026-11-20 10:00:00.123");
        var first=page("?limit=100");assertThat(first.path("items").size()).isEqualTo(100);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var second=page("?limit=100&cursor="+enc(first.path("nextCursor").asText()));
        assertThat(second.path("items").size()).isEqualTo(100);assertThat(second.path("hasMore").asBoolean()).isTrue();
        var last=page("?limit=100&cursor="+enc(second.path("nextCursor").asText()));
        assertThat(last.path("items").size()).isEqualTo(53);assertThat(last.path("hasMore").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"?limit=0","?limit=-1","?limit=101","?limit=no","?type=INVALID","?from=bad","?from=2026-11-20T00:00:00&to=2026-11-19T00:00:00","?from=2026-11-20T00:00:00.123456","?from=0001-01-01T00:00:00","?cursor=","?cursor=garbage"})
    void malformedQueriesAre400(String query) throws Exception { assertThat(get(port,101,"/api/v2/transactions"+query).statusCode()).isEqualTo(400); }
    @Test void filtersMatchLegacyHistoryClockOnUtcPlusSevenAndAreBoundToCursor() throws Exception {
        var detail=JSON.readTree(get(secondPort,101,"/api/v2/transactions/"+ref(3)).body()).path("data");
        String from=detail.path("createdAt").asText(); String to=java.time.LocalDateTime.parse(from).plusSeconds(1).toString();
        var filtered=page("?type=TOPUP&limit=100&from="+enc(from)+"&to="+enc(to));
        assertThat(filtered.path("items").size()).isEqualTo(17);
        for(var row:filtered.path("items")) assertThat(row.path("type").asText()).isEqualTo("TOPUP");
        assertThat(page("?from="+enc(to)).path("items").isEmpty()).isTrue();
        assertThat(page("?to="+enc(from)).path("items").isEmpty()).isTrue();
    }
    @Test void liveTraversalDoesNotRepeatWhenNewerRowsArriveAndMayIncludeBackdatedRows() throws Exception {
        var first=page("?limit=10"); String cursor=first.path("nextCursor").asText();
        insert(200,"TOPUP","2026-11-22 10:00:00.123"); insert(201,"TOPUP","2026-11-19 10:00:00.123");
        var ids=new ArrayList<Long>();
        do {
            var next=page("?limit=100&cursor="+enc(cursor)); for(var row:next.path("items")) ids.add(row.path("id").asLong());
            cursor=next.path("nextCursor").isNull()?null:next.path("nextCursor").asText();
        } while(cursor!=null);
        assertThat(ids).doesNotContain(200L).contains(201L).doesNotHaveDuplicates();
        for(var row:first.path("items")) assertThat(ids).doesNotContain(row.path("id").asLong());
    }
    @Test void cursorRejectsWrongVersionTypesRangeAndTrailingJson() throws Exception {
        String cursor=page("?limit=2").path("nextCursor").asText();
        String original=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
        var invalid=new ArrayList<String>();invalid.add(original+" []");invalid.add("{}");
        for(int field:List.of(0,1,2,3,4)) {
            var array=(com.fasterxml.jackson.databind.node.ArrayNode)JSON.readTree(original);
            array.set(field,JSON.getNodeFactory().nullNode());invalid.add(array.toString());
        }
        for(String id:List.of("-1","0","9223372036854775808","1.5")) {
            var array=(com.fasterxml.jackson.databind.node.ArrayNode)JSON.readTree(original);array.set(4,JSON.readTree(id));invalid.add(array.toString());
        }
        var version=(com.fasterxml.jackson.databind.node.ArrayNode)JSON.readTree(original);version.set(0,JSON.getNodeFactory().numberNode(2));invalid.add(version.toString());
        for(String value:invalid) {
            String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            assertThat(get(port,101,"/api/v2/transactions?cursor="+enc(encoded)).statusCode()).as(value).isEqualTo(400);
        }
    }
    @Test void openApiDeprecatesV1AndDocumentsV2Contracts() throws Exception {
        var response=get(port,0,"/v3/api-docs");assertThat(response.statusCode()).isEqualTo(200);
        var paths=JSON.readTree(response.body()).path("paths");
        assertThat(paths.path("/api/v1/transactions").path("get").path("deprecated").asBoolean()).isTrue();
        assertThat(paths.path("/api/v2/transactions").path("get").path("description").asText()).contains("DST gap/overlap boundaries return 400");
        for(String path:List.of("/api/v2/transactions","/api/v2/transactions/{referenceCode}")) {
            var operation=paths.path(path).path("get");
            for(String code:List.of("200","400","401","404")) assertThat(operation.path("responses").has(code)).as(path+" "+code).isTrue();
            assertThat(operation.path("responses").path("200").path("content").has("application/json")).isTrue();
        }
    }
    @Override @AfterEach void everyCommittedPaymentHasExactlyOneMatchingEventAndNoOrphans() {
        // Synthetic pre-C06 history fixtures exercise reads only; no business writes/outbox are expected.
        assertThat(count("outbox_events")).isZero(); assertThat(count("idempotency_requests")).isZero();
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(500);
    }
}
