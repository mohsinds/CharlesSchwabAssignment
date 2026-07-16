package com.schwab.eventledger.gateway.api;

import com.schwab.eventledger.gateway.config.GatewayProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Optional convenience proxies so clients can query balances through the Gateway.
 * Failures surface as 503 when Account Service is unreachable.
 */
@RestController
@Tag(name = "Accounts (proxy)", description = "Proxy balance/account queries to Account Service")
public class AccountProxyController {

    private static final Logger log = LoggerFactory.getLogger(AccountProxyController.class);

    private final RestClient accountRestClient;

    public AccountProxyController(RestClient accountRestClient, GatewayProperties ignored) {
        this.accountRestClient = accountRestClient;
    }

    @GetMapping("/accounts/{accountId}/balance")
    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService")
    @Operation(summary = "Get account balance via Gateway",
            description = "Proxies to Account Service. Returns 503 if Account is unreachable.")
    public Map<?, ?> getBalance(@PathVariable String accountId) {
        try {
            log.info("Proxying balance query accountId={}", accountId);
            return accountRestClient.get()
                    .uri("/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Service is unreachable");
        }
    }

    @GetMapping("/accounts/{accountId}")
    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService")
    @Operation(summary = "Get account detail via Gateway",
            description = "Proxies to Account Service. Returns 503 if Account is unreachable.")
    public Map<?, ?> getAccount(@PathVariable String accountId) {
        try {
            log.info("Proxying account query accountId={}", accountId);
            return accountRestClient.get()
                    .uri("/accounts/{accountId}", accountId)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Service is unreachable");
        }
    }
}
