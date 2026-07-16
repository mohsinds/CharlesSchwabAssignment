package com.schwab.eventledger.gateway.api;

import com.schwab.eventledger.gateway.domain.EventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Schema(description = "Financial event submitted to the Gateway")
public class EventRequest {

    @NotBlank
    @Schema(example = "evt-001", description = "Idempotency key")
    private String eventId;

    @NotBlank
    @Schema(example = "acct-123")
    private String accountId;

    @NotNull
    @Schema(example = "CREDIT")
    private EventType type;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
    @Schema(example = "150.00")
    private BigDecimal amount;

    @NotBlank
    @Schema(example = "USD")
    private String currency;

    @NotNull
    @Schema(example = "2026-05-15T14:02:11Z")
    private Instant eventTimestamp;

    @Schema(example = "{\"source\":\"mainframe-batch\",\"batchId\":\"B-9042\"}")
    private Map<String, Object> metadata;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
