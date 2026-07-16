package com.schwab.eventledger.account.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class TransactionMetrics {

    private final MeterRegistry meterRegistry;

    public TransactionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Register eagerly so /actuator/prometheus always lists the series
        Counter.builder("transactions_applied_total")
                .description("Transactions applied to the account ledger")
                .tag("result", "created")
                .register(meterRegistry);
        Counter.builder("transactions_applied_total")
                .description("Transactions applied to the account ledger")
                .tag("result", "duplicate")
                .register(meterRegistry);
    }

    public void recordApplied(String result) {
        Counter.builder("transactions_applied_total")
                .description("Transactions applied to the account ledger")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
