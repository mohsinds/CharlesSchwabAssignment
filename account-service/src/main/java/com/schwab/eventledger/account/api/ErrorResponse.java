package com.schwab.eventledger.account.api;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<String> details
) {
    public static ErrorResponse of(int status, String error, String message, List<String> details) {
        return new ErrorResponse(Instant.now(), status, error, message, details);
    }
}
