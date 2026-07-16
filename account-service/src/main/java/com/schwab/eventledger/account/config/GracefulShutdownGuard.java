package com.schwab.eventledger.account.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Marks the service as shutting down so in-flight work can finish cleanly.
 * Paired with {@code server.shutdown=graceful} which drains the Tomcat connector.
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
        log.info("Graceful shutdown started — refusing new work after in-flight requests complete");
    }
}
