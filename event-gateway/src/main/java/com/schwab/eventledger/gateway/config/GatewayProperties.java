package com.schwab.eventledger.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final AccountService accountService = new AccountService();
    private final AsyncFallback asyncFallback = new AsyncFallback();
    private final Outbox outbox = new Outbox();

    public AccountService getAccountService() {
        return accountService;
    }

    public AsyncFallback getAsyncFallback() {
        return asyncFallback;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public static class AccountService {
        private String baseUrl = "http://localhost:8081";
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(2);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class AsyncFallback {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Outbox {
        private long drainIntervalMs = 2000;

        public long getDrainIntervalMs() {
            return drainIntervalMs;
        }

        public void setDrainIntervalMs(long drainIntervalMs) {
            this.drainIntervalMs = drainIntervalMs;
        }
    }
}
