package com.schwab.eventledger.gateway.client;

import com.schwab.eventledger.gateway.api.EventRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestClient accountRestClient;
    private final MeterRegistry meterRegistry;

    public AccountServiceClient(RestClient accountRestClient, MeterRegistry meterRegistry) {
        this.accountRestClient = accountRestClient;
        this.meterRegistry = meterRegistry;
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService")
    public void applyTransaction(EventRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", request.getEventId());
            body.put("type", request.getType().name());
            body.put("amount", request.getAmount());
            body.put("currency", request.getCurrency());
            body.put("eventTimestamp", request.getEventTimestamp().toString());
            if (request.getMetadata() != null) {
                body.put("metadata", request.getMetadata());
            }

            log.info("Calling Account Service apply eventId={} accountId={}",
                    request.getEventId(), request.getAccountId());

            accountRestClient.post()
                    .uri("/accounts/{accountId}/transactions", request.getAccountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } finally {
            sample.stop(Timer.builder("account_service_call_duration_seconds")
                    .description("Latency of Account Service apply calls")
                    .register(meterRegistry));
        }
    }
}
