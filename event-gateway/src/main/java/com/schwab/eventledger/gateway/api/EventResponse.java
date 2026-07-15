package com.schwab.eventledger.gateway.api;

import com.schwab.eventledger.gateway.domain.EventStatus;
import com.schwab.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        EventStatus status,
        Instant createdAt
) {
}
