package com.schwab.eventledger.gateway.metrics;

import com.schwab.eventledger.gateway.domain.EventRepository;
import com.schwab.eventledger.gateway.domain.EventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class EventMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter drainSuccess;

    public EventMetrics(MeterRegistry meterRegistry, EventRepository eventRepository) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("outbox_pending_events", eventRepository,
                        repo -> repo.countByStatus(EventStatus.PENDING))
                .description("Number of PENDING events in the local outbox")
                .register(meterRegistry);
        this.drainSuccess = Counter.builder("outbox_drain_success_total")
                .description("Successful outbox drain applications")
                .register(meterRegistry);
    }

    public void recordSubmit(String result) {
        Counter.builder("events_submitted_total")
                .description("Event submission outcomes")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }

    public void recordDrainSuccess() {
        drainSuccess.increment();
    }
}
