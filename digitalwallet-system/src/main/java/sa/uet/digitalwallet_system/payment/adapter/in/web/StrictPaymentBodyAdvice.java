package sa.uet.digitalwallet_system.payment.adapter.in.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import sa.uet.digitalwallet_system.payment.adapter.in.web.controller.TransactionController;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import java.io.*;
import java.lang.reflect.Type;
import java.util.Set;

@ControllerAdvice(assignableTypes = TransactionController.class)
public class StrictPaymentBodyAdvice extends RequestBodyAdviceAdapter {
    public static final int MAX_BODY_BYTES = 16 * 1024;
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public boolean supports(MethodParameter method, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return Set.of("topup", "withdraw", "transfer").contains(method.getMethod().getName());
    }
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter method, Type type,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        if (message.getHeaders().getContentLength() > MAX_BODY_BYTES) throw tooLarge();
        // Do not trust Content-Length: chunked/unknown or understated bodies must also stay bounded.
        byte[] bytes = message.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw tooLarge();
        try (JsonParser parser = json.createParser(bytes)) {
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) throw invalid();
            boolean transfer = method.getMethod().getName().equals("transfer");
            Set<String> allowed = Set.of(transfer ? "targetWalletNumber" : "linkedBankAccountId", "amount", "description");
            var fields = root.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
            for (String field : Set.of("amount", "linkedBankAccountId")) {
                JsonNode value = root.get(field);
                if (value != null && !value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong())) throw invalid();
            }
            for (String field : Set.of("targetWalletNumber", "description")) {
                JsonNode value = root.get(field);
                if (value != null && !value.isNull() && !value.isTextual()) throw invalid();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw invalid(); }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
    private PaymentFailure tooLarge() {
        return new PaymentFailure("PAYLOAD_TOO_LARGE", "Payment body must not exceed 16384 bytes", false);
    }
    private PaymentFailure invalid() { return new PaymentFailure("INVALID_REQUEST", "Invalid payment JSON", false); }
}
