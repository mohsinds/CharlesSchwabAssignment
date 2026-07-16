package com.schwab.eventledger.gateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "Health", description = "Liveness and database diagnostic")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Reports service and local H2 database status")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "event-gateway");
        String database = "UP";
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                database = "DOWN";
            }
        } catch (Exception ex) {
            database = "DOWN";
        }
        body.put("database", database);
        body.put("status", "UP".equals(database) ? "UP" : "DOWN");
        return body;
    }
}
