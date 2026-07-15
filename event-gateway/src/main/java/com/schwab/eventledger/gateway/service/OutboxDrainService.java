package com.schwab.eventledger.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.eventledger.gateway.api.EventRequest;
import com.schwab.eventledger.gateway.client.AccountServiceClient;
import com.schwab.eventledger.gateway.domain.EventEntity;
import com.schwab.eventledger.gateway.domain.EventRepository;
import com.schwab.eventledger.gateway.domain.EventStatus;
import com.schwab.eventledger.gateway.metrics.EventMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class OutboxDrainService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final EventMetrics eventMetrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OutboxDrainService(
            EventRepository eventRepository,
            AccountServiceClient accountServiceClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            EventMetrics eventMetrics,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.eventMetrics = eventMetrics;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${gateway.outbox.drain-interval-ms:2000}")
    public void drain() {
        CircuitBreaker.State state = circuitBreakerRegistry.circuitBreaker("accountService").getState();
        if (state == CircuitBreaker.State.OPEN) {
            log.debug("Skipping outbox drain; circuit breaker is OPEN");
            return;
        }

        List<EventEntity> pending = eventRepository.findByStatusOrderByCreatedAtAsc(EventStatus.PENDING);
        for (EventEntity event : pending) {
            tryApply(event.getEventId());
        }
    }

    private void tryApply(String eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            EventEntity managed = eventRepository.findById(eventId).orElse(null);
            if (managed == null || managed.getStatus() != EventStatus.PENDING) {
                return;
            }

            EventRequest request = toRequest(managed);
            try {
                accountServiceClient.applyTransaction(request);
                managed.setStatus(EventStatus.APPLIED);
                managed.setAttemptCount(managed.getAttemptCount() + 1);
                eventRepository.save(managed);
                eventMetrics.recordDrainSuccess();
                log.info("Outbox drained eventId={} to APPLIED", managed.getEventId());
            } catch (Exception ex) {
                managed.setAttemptCount(managed.getAttemptCount() + 1);
                eventRepository.save(managed);
                log.warn("Outbox drain failed eventId={} attempt={}: {}",
                        managed.getEventId(), managed.getAttemptCount(), ex.toString());
            }
        });
    }

    private EventRequest toRequest(EventEntity entity) {
        EventRequest request = new EventRequest();
        request.setEventId(entity.getEventId());
        request.setAccountId(entity.getAccountId());
        request.setType(entity.getType());
        request.setAmount(entity.getAmount());
        request.setCurrency(entity.getCurrency());
        request.setEventTimestamp(entity.getEventTimestamp());
        request.setMetadata(readMetadata(entity.getMetadataJson()));
        return request;
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
