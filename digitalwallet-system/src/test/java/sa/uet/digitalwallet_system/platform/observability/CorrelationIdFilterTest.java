package sa.uet.digitalwallet_system.platform.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {
    private final MdcCorrelationIdProvider provider = new MdcCorrelationIdProvider();
    private final CorrelationIdFilter filter = new CorrelationIdFilter(provider);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void missingHeaderGeneratesEchoesAndCleansAUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wallets/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            String effective = response.getHeader(CorrelationIdFilter.HEADER);
            assertThat(UUID.fromString(effective).version()).isEqualTo(4);
            assertThat(MDC.get(MdcCorrelationIdProvider.CORRELATION_KEY)).isEqualTo(effective);
            assertThat(MDC.get(MdcCorrelationIdProvider.TRACE_KEY)).isEqualTo(effective);
            assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(effective);
        });

        assertThat(MDC.get(MdcCorrelationIdProvider.CORRELATION_KEY)).isNull();
        assertThat(MDC.get(MdcCorrelationIdProvider.TRACE_KEY)).isNull();
    }

    @Test
    void validUppercaseUuidIsNormalizedAndPriorMdcIsRestored() throws Exception {
        String supplied = "A0B1C2D3-E4F5-4A67-8B90-1234567890AB";
        MDC.put(MdcCorrelationIdProvider.CORRELATION_KEY, "prior-correlation");
        MDC.put(MdcCorrelationIdProvider.TRACE_KEY, "prior-trace");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transactions/transfer");
        request.addHeader(CorrelationIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                assertThat(provider.current()).contains(supplied.toLowerCase()));

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(supplied.toLowerCase());
        assertThat(MDC.get(MdcCorrelationIdProvider.CORRELATION_KEY)).isEqualTo("prior-correlation");
        assertThat(MDC.get(MdcCorrelationIdProvider.TRACE_KEY)).isEqualTo("prior-trace");
    }

    @Test
    void invalidBlankAndMultipleHeadersAreReplacedRatherThanReflected() throws Exception {
        for (List<String> headers : List.of(List.of(" attacker supplied "), List.of(""),
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()))) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions");
            headers.forEach(value -> request.addHeader(CorrelationIdFilter.HEADER, value));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

            String effective = response.getHeader(CorrelationIdFilter.HEADER);
            assertThatCodeIsUuid(effective);
            assertThat(headers).doesNotContain(effective);
        }
    }

    @Test
    void exceptionStillCleansMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/failure");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(MdcCorrelationIdProvider.CORRELATION_KEY)).isNull();
        assertThat(MDC.get(MdcCorrelationIdProvider.TRACE_KEY)).isNull();
        assertThatCodeIsUuid(response.getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    void concurrentRequestsNeverShareThreadLocalCorrelation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                String expected = UUID.randomUUID().toString();
                tasks.add(() -> {
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/parallel");
                    request.addHeader(CorrelationIdFilter.HEADER, expected);
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                            assertThat(provider.current()).contains(expected));
                    assertThat(provider.current()).isEmpty();
                    return response.getHeader(CorrelationIdFilter.HEADER);
                });
            }
            List<String> actual = executor.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
            assertThat(actual).doesNotHaveDuplicates();
        }
    }

    private static void assertThatCodeIsUuid(String value) {
        assertThat(value).isNotNull();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }
}
