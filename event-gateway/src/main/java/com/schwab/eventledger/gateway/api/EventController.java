package com.schwab.eventledger.gateway.api;

import com.schwab.eventledger.gateway.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
@Tag(name = "Events", description = "Submit and query ledger events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(summary = "Submit an event",
            description = "Idempotent on eventId. Returns 201 when applied, 200 for duplicates, "
                    + "202 when queued (Account unavailable), 429 when rate limited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Applied immediately"),
            @ApiResponse(responseCode = "200", description = "Duplicate already applied"),
            @ApiResponse(responseCode = "202", description = "Queued as PENDING (async fallback)"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
            @ApiResponse(responseCode = "503", description = "Account unavailable and fallback disabled")
    })
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        log.info("Received event submission eventId={} accountId={}", request.getEventId(), request.getAccountId());
        EventService.SubmitResult result = eventService.submit(request);
        return ResponseEntity.status(result.status()).body(result.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get event by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event found"),
            @ApiResponse(responseCode = "404", description = "Event not found")
    })
    public EventResponse getById(@PathVariable("id") String id) {
        log.info("Fetching event id={}", id);
        return eventService.getById(id);
    }

    @GetMapping
    @Operation(summary = "List events for an account",
            description = "Ordered by eventTimestamp ASC, then eventId ASC")
    public List<EventResponse> listByAccount(@RequestParam("account") String accountId) {
        log.info("Listing events accountId={}", accountId);
        return eventService.listByAccount(accountId);
    }
}
