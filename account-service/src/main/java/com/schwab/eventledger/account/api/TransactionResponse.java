package com.schwab.eventledger.account.api;

import com.schwab.eventledger.account.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        Instant appliedAt
) {
}
