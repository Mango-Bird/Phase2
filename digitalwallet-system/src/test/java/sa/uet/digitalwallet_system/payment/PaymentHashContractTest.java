package sa.uet.digitalwallet_system.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import sa.uet.digitalwallet_system.payment.adapter.serialization.PaymentJsonEncoding;
import sa.uet.digitalwallet_system.payment.api.PaymentOperation;
import sa.uet.digitalwallet_system.payment.application.PaymentIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentHashContractTest {
    @TestFactory List<DynamicTest> acceptedCrossParserVectorsMatchExactly() throws Exception {
        var vectors = new ObjectMapper().readTree(Files.readString(Path.of("../digitalwallet-docs/phase2/contracts/fixtures/idempotency-v1.json"))).get("vectors");
        var codec = new PaymentJsonEncoding();
        List<DynamicTest> tests = new ArrayList<>();
        for (var vector : vectors) tests.add(DynamicTest.dynamicTest(vector.get("name").asText(), () -> {
            var f = vector.get("canonicalFields");
            var intent = new PaymentIntent(f.get(1).asLong(), PaymentOperation.valueOf(f.get(2).asText()), f.get(3).asLong(),
                    f.get(4).isNull() ? null : f.get(4).asLong(), f.get(5).isNull() ? null : f.get(5).asText(),
                    f.get(6).asLong(), f.get(7).isNull() ? null : f.get(7).asText());
            assertThat(codec.canonical(intent)).isEqualTo(vector.get("canonicalUtf8").asText());
            assertThat(codec.requestHash(intent)).isEqualTo(vector.get("sha256").asText());
        }));
        return tests;
    }
}
