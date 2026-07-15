package com.schwab.eventledger.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "gateway.account-service.base-url=http://localhost:18083",
        "resilience4j.ratelimiter.instances.postEvents.limitForPeriod=2",
        "resilience4j.ratelimiter.instances.postEvents.limitRefreshPeriod=1m",
        "resilience4j.ratelimiter.instances.postEvents.timeoutDuration=0"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitTest {

    private WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(18083));
        wireMock.start();
        WireMock.configureFor("localhost", 18083);
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
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));
    }

    @Test
    void returns429WhenRateLimitExceeded() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("evt-rl-" + i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("evt-rl-overflow")))
                .andExpect(status().isTooManyRequests());
    }

    private String json(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "acct-rl",
                  "type": "CREDIT",
                  "amount": 1.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:00:00Z"
                }
                """.formatted(eventId);
    }
}
