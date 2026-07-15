package com.schwab.eventledger.gateway.pact;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "account-service", pactVersion = PactSpecVersion.V3)
class AccountServiceConsumerPactTest {

    @Pact(consumer = "event-gateway")
    RequestResponsePact applyTransactionPact(PactDslWithProvider builder) {
        return builder
                .given("account acct-pact can accept transactions")
                .uponReceiving("a request to apply a credit transaction")
                .path("/accounts/acct-pact/transactions")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("eventId", "evt-pact-1")
                        .stringType("type", "CREDIT")
                        .decimalType("amount", 25.50)
                        .stringType("currency", "USD")
                        .stringType("eventTimestamp", "2026-05-15T14:00:00Z"))
                .willRespondWith()
                .status(201)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("eventId", "evt-pact-1")
                        .stringType("accountId", "acct-pact")
                        .stringType("type", "CREDIT")
                        .decimalType("amount", 25.50)
                        .stringType("currency", "USD")
                        .stringMatcher("eventTimestamp", ".*", "2026-05-15T14:00:00Z")
                        .stringMatcher("appliedAt", ".*", "2026-05-15T14:05:00Z"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "applyTransactionPact")
    void verifyApplyTransactionContract(MockServer mockServer) {
        RestClient client = RestClient.builder().baseUrl(mockServer.getUrl()).build();
        Map<?, ?> response = client.post()
                .uri("/accounts/acct-pact/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "eventId", "evt-pact-1",
                        "type", "CREDIT",
                        "amount", 25.50,
                        "currency", "USD",
                        "eventTimestamp", "2026-05-15T14:00:00Z"))
                .retrieve()
                .body(Map.class);

        assertThat(response).isNotNull();
        assertThat(response.get("eventId")).isEqualTo("evt-pact-1");
        assertThat(response.get("accountId")).isEqualTo("acct-pact");
    }
}
