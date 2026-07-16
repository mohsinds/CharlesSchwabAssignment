package com.schwab.eventledger.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.schwab.eventledger.gateway.domain.EventRepository;
import com.schwab.eventledger.gateway.domain.EventStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Epic("Event Gateway")
@Feature("Retry")
@SpringBootTest(properties = {
        "gateway.account-service.base-url=http://localhost:18084",
        "gateway.async-fallback.enabled=true",
        "resilience4j.circuitbreaker.instances.accountService.minimumNumberOfCalls=20",
        "resilience4j.retry.instances.accountService.maxAttempts=3",
        "resilience4j.retry.instances.accountService.waitDuration=20ms"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryBehaviorTest {

    private WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private EventRepository eventRepository;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(18084));
        wireMock.start();
        WireMock.configureFor("localhost", 18084);
    }

    @AfterAll
    void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        eventRepository.deleteAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    @Test
    @Story("Transient 5xx then success")
    @DisplayName("Retries twice then applies event on third attempt")
    void retriesTransientFailuresThenSucceeds() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second"));

        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("ok"));

        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("retry")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-retry-1", "acct-retry")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        wireMock.verify(3, postRequestedFor(urlPathMatching("/accounts/acct-retry/transactions")));
    }

    @Test
    @Story("Exhausted retries queue locally")
    @DisplayName("After maxAttempts of 500s, event is PENDING with 202")
    void exhaustsRetriesThenQueuesPending() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-retry-exhaust", "acct-retry-x")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        wireMock.verify(3, postRequestedFor(urlPathMatching("/accounts/acct-retry-x/transactions")));
        assertThat(eventRepository.findById("evt-retry-exhaust")).isPresent()
                .get()
                .extracting(e -> e.getStatus())
                .isEqualTo(EventStatus.PENDING);
    }

    @Test
    @Story("No retry on client errors")
    @DisplayName("Account 422 is not retried")
    void doesNotRetryClientErrors() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":422,"error":"Unprocessable Entity","message":"Insufficient funds","details":[]}
                                """)));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("evt-retry-422", "acct-retry-4xx")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient funds")));

        wireMock.verify(1, postRequestedFor(urlPathMatching("/accounts/acct-retry-4xx/transactions")));
        assertThat(eventRepository.findById("evt-retry-422")).isEmpty();
    }

    private String eventJson(String eventId, String accountId) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "CREDIT",
                  "amount": 10.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:00:00Z"
                }
                """.formatted(eventId, accountId);
    }
}
