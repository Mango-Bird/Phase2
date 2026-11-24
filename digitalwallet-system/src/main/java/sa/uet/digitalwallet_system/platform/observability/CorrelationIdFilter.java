package sa.uet.digitalwallet_system.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import sa.uet.digitalwallet_system.platform.api.observability.CorrelationIdProvider;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".effectiveId";

    private final CorrelationIdProvider correlationIds;

    @Autowired
    public CorrelationIdFilter(ObjectProvider<CorrelationIdProvider> correlationIds) {
        this(correlationIds.getIfAvailable(MdcCorrelationIdProvider::new));
    }

    CorrelationIdFilter(CorrelationIdProvider correlationIds) {
        this.correlationIds = correlationIds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        String supplied = values.size() == 1 ? values.getFirst() : null;
        boolean accepted = values.size() == 1 && CorrelationIds.isValid(supplied);
        String effective = CorrelationIds.normalizeOrCreate(accepted ? supplied : null);
        if (!values.isEmpty() && !accepted) {
            log.debug("Rejected untrusted correlation header; generated a replacement");
        }
        request.setAttribute(REQUEST_ATTRIBUTE, effective);
        response.setHeader(HEADER, effective);
        try (CorrelationIdProvider.Scope ignored = correlationIds.install(effective)) {
            chain.doFilter(request, response);
        }
    }
}
