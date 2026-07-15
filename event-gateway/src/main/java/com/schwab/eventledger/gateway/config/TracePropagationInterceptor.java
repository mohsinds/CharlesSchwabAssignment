package com.schwab.eventledger.gateway.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Ensures W3C traceparent is always set on outbound Account Service calls so both
 * services share one trace path even when observation wiring differs by environment.
 */
@Component
public class TracePropagationInterceptor implements ClientHttpRequestInterceptor {

    private final Tracer tracer;

    public TracePropagationInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Span current = tracer.currentSpan();
        if (current != null && !request.getHeaders().containsKey("traceparent")) {
            String traceId = current.context().traceId();
            String spanId = current.context().spanId();
            request.getHeaders().set("traceparent", "00-%s-%s-01".formatted(traceId, spanId));
        }
        return execution.execute(request, body);
    }
}
