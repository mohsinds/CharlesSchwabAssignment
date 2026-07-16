package com.schwab.eventledger.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.schwab.eventledger.gateway.config.GracefulShutdownGuard;
import com.schwab.eventledger.gateway.domain.EventEntity;
import com.schwab.eventledger.gateway.domain.EventRepository;
import com.schwab.eventledger.gateway.domain.EventStatus;
import com.schwab.eventledger.gateway.domain.EventType;
import com.schwab.eventledger.gateway.service.OutboxDrainService;
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
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Event Gateway")
@Feature("Graceful shutdown")
@SpringBootTest(properties = {
        "gateway.account-service.base-url=http://localhost:18090",
        "gateway.async-fallback.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GracefulShutdownTest {

    private WireMockServer wireMock;

    @Autowired
    private GracefulShutdownGuard gracefulShutdownGuard;

    @Autowired
    private OutboxDrainService outboxDrainService;

    @Autowired
    private EventRepository eventRepository;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(18090));
        wireMock.start();
        WireMock.configureFor("localhost", 18090);
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
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));
    }

    @Test
    @Story("Pause outbox drain during shutdown")
    @DisplayName("drain() skips PENDING events after GracefulShutdownGuard.onShutdown()")
    void drainSkipsPendingWhenShuttingDown() {
        EventEntity pending = new EventEntity();
        pending.setEventId("evt-shutdown-1");
        pending.setAccountId("acct-shutdown");
        pending.setType(EventType.CREDIT);
        pending.setAmount(new BigDecimal("10.00"));
        pending.setCurrency("USD");
        pending.setEventTimestamp(Instant.parse("2026-05-15T14:00:00Z"));
        pending.setStatus(EventStatus.PENDING);
        pending.setCreatedAt(Instant.now());
        pending.setAttemptCount(0);
        eventRepository.save(pending);

        gracefulShutdownGuard.onShutdown();
        assertThat(gracefulShutdownGuard.isShuttingDown()).isTrue();

        outboxDrainService.drain();

        assertThat(eventRepository.findById("evt-shutdown-1")).isPresent()
                .get()
                .extracting(EventEntity::getStatus)
                .isEqualTo(EventStatus.PENDING);

        wireMock.verify(0, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }
}
