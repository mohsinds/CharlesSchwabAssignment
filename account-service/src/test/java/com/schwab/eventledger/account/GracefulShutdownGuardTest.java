package com.schwab.eventledger.account;

import com.schwab.eventledger.account.config.GracefulShutdownGuard;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Account Service")
@Feature("Graceful shutdown")
class GracefulShutdownGuardTest {

    @Test
    @Story("Shutdown flag")
    @DisplayName("onShutdown() marks the service as shutting down")
    void onShutdownSetsFlag() {
        GracefulShutdownGuard guard = new GracefulShutdownGuard();
        assertThat(guard.isShuttingDown()).isFalse();
        guard.onShutdown();
        assertThat(guard.isShuttingDown()).isTrue();
    }
}
