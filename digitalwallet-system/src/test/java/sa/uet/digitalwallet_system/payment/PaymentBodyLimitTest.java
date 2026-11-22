package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import sa.uet.digitalwallet_system.payment.adapter.in.web.StrictPaymentBodyAdvice;
import sa.uet.digitalwallet_system.payment.adapter.in.web.controller.TransactionController;
import sa.uet.digitalwallet_system.payment.adapter.in.web.dto.request.TopupRequest;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class PaymentBodyLimitTest {
    static final int LIMIT = 16*1024;
    final StrictPaymentBodyAdvice advice = new StrictPaymentBodyAdvice();
    MethodParameter parameter() throws Exception {
        return new MethodParameter(TransactionController.class.getMethod("topup", Long.class, String.class, TopupRequest.class), 2);
    }
    HttpInputMessage read(HttpInputMessage message) throws Exception {
        return advice.beforeBodyRead(message, parameter(), TopupRequest.class, null);
    }
    HttpInputMessage input(HttpHeaders headers, InputStream stream) {
        return new HttpInputMessage() {
            public HttpHeaders getHeaders() { return headers; }
            public InputStream getBody() { return stream; }
        };
    }
    void assertTooLarge(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
        assertThatThrownBy(request).isInstanceOfSatisfying(PaymentFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("PAYLOAD_TOO_LARGE");
            assertThat(failure.retryable()).isFalse();
        });
    }
    @Test void oversizedDeclaredLengthIsRejectedWithoutOpeningStream() {
        HttpHeaders headers = new HttpHeaders(); headers.setContentLength(LIMIT+1);
        assertTooLarge(() -> read(new HttpInputMessage() {
            public HttpHeaders getHeaders() { return headers; }
            public InputStream getBody() { throw new AssertionError("Must reject declared length before opening the body"); }
        }));
    }
    @Test void unknownLengthReadsAtMostOneByteBeyondLimit() {
        GuardedStream stream = new GuardedStream();
        assertTooLarge(() -> read(input(new HttpHeaders(), stream)));
        assertThat(stream.readBytes).isEqualTo(LIMIT+1);
    }
    @Test void understatedLengthCannotBypassActualStreamLimit() {
        HttpHeaders headers = new HttpHeaders(); headers.setContentLength(1);
        GuardedStream stream = new GuardedStream();
        assertTooLarge(() -> read(input(headers, stream)));
        assertThat(stream.readBytes).isEqualTo(LIMIT+1);
    }
    @Test void validJsonAtExactlyTheLimitRoundTripsUnchanged() throws Exception {
        String json = "{\"linkedBankAccountId\":31,\"amount\":100}";
        byte[] bytes = (" ".repeat(LIMIT-json.length())+json).getBytes(StandardCharsets.UTF_8);
        assertThat(read(input(new HttpHeaders(), new ByteArrayInputStream(bytes))).getBody().readAllBytes()).isEqualTo(bytes);
    }
    /** Fails immediately if a reader tries to consume beyond the contract cap; never allocates a huge test body. */
    static class GuardedStream extends InputStream {
        int readBytes;
        public int read() throws IOException {
            if (readBytes >= LIMIT+1) throw new IOException("Reader exceeded body cap");
            readBytes++; return ' ';
        }
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length > LIMIT+1-readBytes) throw new IOException("Reader exceeded body cap");
            Arrays.fill(buffer, offset, offset+length, (byte)' '); readBytes += length; return length;
        }
    }
}
