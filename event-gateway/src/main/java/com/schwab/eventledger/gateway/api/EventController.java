package com.schwab.eventledger.gateway.api;

import com.schwab.eventledger.gateway.service.EventService;
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
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        log.info("Received event submission eventId={} accountId={}", request.getEventId(), request.getAccountId());
        EventService.SubmitResult result = eventService.submit(request);
        return ResponseEntity.status(result.status()).body(result.response());
    }

    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable("id") String id) {
        log.info("Fetching event id={}", id);
        return eventService.getById(id);
    }

    @GetMapping
    public List<EventResponse> listByAccount(@RequestParam("account") String accountId) {
        log.info("Listing events accountId={}", accountId);
        return eventService.listByAccount(accountId);
    }
}
