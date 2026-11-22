package sa.uet.digitalwallet_system.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sa.uet.digitalwallet_system.payment.adapter.serialization.PaymentJsonEncoding;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentSnapshotContractTest {
    @ParameterizedTest @ValueSource(strings = {"TOPUP", "WITHDRAW", "TRANSFER"})
    void snapshotPreservesLegacyFieldsIncludingExplicitNulls(String operation) throws Exception {
        var tx = Transaction.builder().id(7L).referenceCode("reference").type(operation)
                .sourceWalletId(operation.equals("TOPUP") ? null : 11L)
                .targetWalletId(operation.equals("WITHDRAW") ? null : 22L)
                .linkedBankAccountId(operation.equals("TRANSFER") ? null : 31L)
                .amount(100L).description(null).createdAt(LocalDateTime.of(2026,11,22,1,2,3)).build();
        var root = new ObjectMapper().readTree(new PaymentJsonEncoding().successSnapshot(tx));
        assertThat(root.get("status").asInt()).isEqualTo(200);
        assertThat(root.get("timestamp").isTextual()).isTrue();
        var data = root.get("data");
        assertThat(data.size()).isEqualTo(9);
        for (String field : java.util.List.of("id","referenceCode","type","sourceWalletId","targetWalletId","linkedBankAccountId","amount","description","createdAt"))
            assertThat(data.has(field)).as(field).isTrue();
        assertThat(data.get("description").isNull()).isTrue();
        assertThat(data.get("createdAt").asText()).isEqualTo("2026-11-22T01:02:03");
        assertThat(data.has("idempotencyKey")).isFalse();
        assertThat(data.has("requestHash")).isFalse();
    }
}
