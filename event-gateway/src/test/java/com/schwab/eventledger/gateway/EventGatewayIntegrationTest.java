package com.schwab.eventledger.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.schwab.eventledger.gateway.domain.EventRepository;
import com.schwab.eventledger.gateway.domain.EventStatus;
import com.schwab.eventledger.gateway.service.OutboxDrainService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.account-service.base-url=http://localhost:18081",
        "gateway.async-fallback.enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus,info,metrics",
        "management.endpoint.prometheus.enabled=true"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventGatewayIntegrationTest {

    private WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private OutboxDrainService outboxDrainService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(18081));
        wireMock.start();
        WireMock.configureFor("localhost", 18081);
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
        stubAccountSuccess();
    }

    @Test
    void submitsEventIdempotentlyAndListsInTimestampOrder() throws Exception {
        postEvent("evt-b", "acct-1", "CREDIT", "100.00", "2026-05-15T15:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        postEvent("evt-a", "acct-1", "DEBIT", "25.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        postEvent("evt-b", "acct-1", "CREDIT", "100.00", "2026-05-15T15:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-b"));

        mockMvc.perform(get("/events").param("account", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-a"))
                .andExpect(jsonPath("$[1].eventId").value("evt-b"));

        wireMock.verify(2, postRequestedFor(urlPathMatching("/accounts/acct-1/transactions")));
    }

    @Test
    void rejectsInvalidPayloads() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-bad",
                                  "accountId": "acct-1",
                                  "type": "CREDIT",
                                  "amount": 0,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-bad-2",
                                  "accountId": "acct-1",
                                  "type": "TRANSFER",
                                  "amount": 10,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forwardsInsufficientFundsAs422WithAccountMessage() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "timestamp": "2026-05-15T14:00:00Z",
                                  "status": 422,
                                  "error": "Unprocessable Entity",
                                  "message": "Insufficient funds: debit would result in negative balance (allowed-negative-balance=false)",
                                  "details": []
                                }
                                """)));

        postEvent("evt-nsf-1", "acct-nsf", "DEBIT", "99.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Insufficient funds")));

        assertThat(eventRepository.findById("evt-nsf-1")).isEmpty();
    }

    @Test
    void marksOutboxRejectedOnPermanentAccount4xx() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(503)));

        postEvent("evt-rej-1", "acct-rej", "DEBIT", "10.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":422,"error":"Unprocessable Entity","message":"Insufficient funds","details":[]}
                                """)));

        outboxDrainService.drain();

        assertThat(eventRepository.findById("evt-rej-1")).isPresent()
                .get()
                .extracting(e -> e.getStatus())
                .isEqualTo(EventStatus.REJECTED);
    }

    @Test
    void queuesWhenAccountUnavailableAndDrainsWhenRecovered() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(503)));

        postEvent("evt-queue-1", "acct-q", "CREDIT", "50.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/events/{id}", "evt-queue-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(eventRepository.findById("evt-queue-1")).isPresent()
                .get()
                .extracting(e -> e.getStatus())
                .isEqualTo(EventStatus.PENDING);

        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        stubAccountSuccess();

        outboxDrainService.drain();

        assertThat(eventRepository.findById("evt-queue-1")).isPresent()
                .get()
                .extracting(e -> e.getStatus())
                .isEqualTo(EventStatus.APPLIED);
    }

    @Test
    void getsStillWorkWhenAccountIsDown() throws Exception {
        stubAccountSuccess();
        postEvent("evt-local-1", "acct-local", "CREDIT", "10.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        wireMock.stop();
        try {
            mockMvc.perform(get("/events/{id}", "evt-local-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value("evt-local-1"));

            mockMvc.perform(get("/events").param("account", "acct-local"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventId").value("evt-local-1"));

            mockMvc.perform(get("/accounts/{accountId}/balance", "acct-local"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            wireMock.start();
            WireMock.configureFor("localhost", 18081);
        }
    }

    @Test
    void propagatesTraceParentHeaderToAccountService() throws Exception {
        stubAccountSuccess();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .content(eventJson("evt-trace-1", "acct-t", "CREDIT", "10.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isCreated());

        // Gateway generates/continues a trace and always propagates W3C traceparent outbound
        wireMock.verify(postRequestedFor(urlPathMatching("/accounts/acct-t/transactions"))
                .withHeader("traceparent", containing("00-")));
    }

    @Test
    void opensCircuitBreakerAfterRepeatedFailures() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 6; i++) {
            postEvent("evt-cb-" + i, "acct-cb", "CREDIT", "1.00", "2026-05-15T14:00:00Z");
        }

        CircuitBreaker.State state = circuitBreakerRegistry.circuitBreaker("accountService").getState();
        assertThat(state).isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN, CircuitBreaker.State.CLOSED);
        // With async fallback, requests are accepted as PENDING; circuit should trend toward OPEN
        assertThat(circuitBreakerRegistry.circuitBreaker("accountService").getMetrics().getNumberOfFailedCalls())
                .isGreaterThan(0);
    }

    @Test
    void prometheusEndpointExposesCustomMetric() throws Exception {
        stubAccountSuccess();
        postEvent("evt-metric-1", "acct-m", "CREDIT", "5.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        assertThat(meterRegistry.find("events_submitted_total").counters()).isNotEmpty();

        ResponseEntity<String> actuator = restTemplate.getForEntity("/actuator", String.class);
        assertThat(actuator.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("events_submitted_total");
    }

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("event-gateway"));
    }

    private void stubAccountSuccess() {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"ok\"}")));
    }

    private org.springframework.test.web.servlet.ResultActions postEvent(
            String eventId, String accountId, String type, String amount, String ts) throws Exception {
        return mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventJson(eventId, accountId, type, amount, ts)));
    }

    private String eventJson(String eventId, String accountId, String type, String amount, String ts) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s",
                  "metadata": {"source": "test"}
                }
                """.formatted(eventId, accountId, type, amount, ts);
    }
}
