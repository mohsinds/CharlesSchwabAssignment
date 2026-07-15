package com.schwab.eventledger.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.eventledger.gateway.api.EventRequest;
import com.schwab.eventledger.gateway.api.EventResponse;
import com.schwab.eventledger.gateway.client.AccountServiceClient;
import com.schwab.eventledger.gateway.config.GatewayProperties;
import com.schwab.eventledger.gateway.domain.EventEntity;
import com.schwab.eventledger.gateway.domain.EventRepository;
import com.schwab.eventledger.gateway.domain.EventStatus;
import com.schwab.eventledger.gateway.metrics.EventMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final GatewayProperties gatewayProperties;
    private final EventMetrics eventMetrics;
    private final ObjectMapper objectMapper;

    public EventService(
            EventRepository eventRepository,
            AccountServiceClient accountServiceClient,
            GatewayProperties gatewayProperties,
            EventMetrics eventMetrics,
            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.gatewayProperties = gatewayProperties;
        this.eventMetrics = eventMetrics;
        this.objectMapper = objectMapper;
    }

    public record SubmitResult(EventResponse response, HttpStatus status) {
    }

    @RateLimiter(name = "postEvents")
    @Transactional
    public SubmitResult submit(EventRequest request) {
        Optional<EventEntity> existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            EventEntity event = existing.get();
            eventMetrics.recordSubmit("duplicate");
            HttpStatus status = event.getStatus() == EventStatus.PENDING
                    ? HttpStatus.ACCEPTED
                    : HttpStatus.OK;
            return new SubmitResult(toResponse(event), status);
        }

        try {
            accountServiceClient.applyTransaction(request);
            EventEntity applied = persist(request, EventStatus.APPLIED);
            eventMetrics.recordSubmit("created");
            return new SubmitResult(toResponse(applied), HttpStatus.CREATED);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                eventMetrics.recordSubmit("rejected");
                throw new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()),
                        "Account Service rejected the transaction");
            }
            return handleAccountUnavailable(request, ex);
        } catch (CallNotPermittedException | RestClientException ex) {
            return handleAccountUnavailable(request, ex);
        }
    }

    private SubmitResult handleAccountUnavailable(EventRequest request, Exception ex) {
        log.warn("Account Service unavailable for eventId={}: {}", request.getEventId(), ex.toString());
        if (gatewayProperties.getAsyncFallback().isEnabled()) {
            EventEntity pending = persist(request, EventStatus.PENDING);
            eventMetrics.recordSubmit("queued");
            return new SubmitResult(toResponse(pending), HttpStatus.ACCEPTED);
        }
        eventMetrics.recordSubmit("unavailable");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Account Service is unavailable");
    }

    @Transactional(readOnly = true)
    public EventResponse getById(String eventId) {
        return eventRepository.findById(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId).stream()
                .map(this::toResponse)
                .toList();
    }

    private EventEntity persist(EventRequest request, EventStatus status) {
        EventEntity entity = new EventEntity();
        entity.setEventId(request.getEventId());
        entity.setAccountId(request.getAccountId());
        entity.setType(request.getType());
        entity.setAmount(request.getAmount());
        entity.setCurrency(request.getCurrency());
        entity.setEventTimestamp(request.getEventTimestamp());
        entity.setMetadataJson(writeMetadata(request.getMetadata()));
        entity.setStatus(status);
        entity.setCreatedAt(Instant.now());
        entity.setAttemptCount(0);
        return eventRepository.save(entity);
    }

    private EventResponse toResponse(EventEntity entity) {
        return new EventResponse(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getEventTimestamp(),
                readMetadata(entity.getMetadataJson()),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata");
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}
