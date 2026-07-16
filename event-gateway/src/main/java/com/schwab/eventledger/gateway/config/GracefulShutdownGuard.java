package com.schwab.eventledger.gateway.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinates graceful shutdown: pause outbox drain and let Tomcat finish in-flight HTTP.
 * Paired with {@code server.shutdown=graceful}.
 */
@Component
public class GracefulShutdownGuard {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownGuard.class);

    private volatile boolean shuttingDown;

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @PreDestroy
    public void onShutdown() {
        shuttingDown = true;
        log.info("Graceful shutdown started — pausing outbox drain; finishing in-flight requests");
    }
}
