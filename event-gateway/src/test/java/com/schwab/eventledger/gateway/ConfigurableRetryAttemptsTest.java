package com.schwab.eventledger.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Epic("Event Gateway")
@Feature("Retry")
@SpringBootTest(properties = {
        "gateway.account-service.base-url=http://localhost:18085",
        "gateway.async-fallback.enabled=true",
        "resilience4j.circuitbreaker.instances.accountService.minimumNumberOfCalls=20",
        "resilience4j.retry.instances.accountService.maxAttempts=2",
        "resilience4j.retry.instances.accountService.waitDuration=20ms"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigurableRetryAttemptsTest {

    private WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(18085));
        wireMock.start();
        WireMock.configureFor("localhost", 18085);
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
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    @Test
    @Story("Configurable maxAttempts")
    @DisplayName("maxAttempts=2 yields exactly two Account calls before success")
    void respectsConfiguredMaxAttemptsOfTwo() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("retry2")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("ok"));

        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("retry2")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-retry-cfg-1",
                                  "accountId": "acct-retry-cfg",
                                  "type": "CREDIT",
                                  "amount": 10.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        wireMock.verify(2, postRequestedFor(urlPathMatching("/accounts/acct-retry-cfg/transactions")));
    }
}
