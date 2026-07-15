package com.schwab.eventledger.gateway.api;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrometheusController {

    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public PrometheusController(PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    /**
     * Explicit scrape endpoint (in addition to Spring Boot's actuator mapping when available).
     * Guarantees a Prometheus metrics URL for local demos and Compose scraping.
     */
    @GetMapping(path = {"/actuator/prometheus", "/prometheus"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String scrape() {
        return prometheusMeterRegistry.scrape();
    }
}
