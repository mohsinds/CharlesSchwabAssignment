package com.schwab.eventledger.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Gateway API")
                        .description("""
                                Public API for the Event Ledger.
                                Validates and stores events, applies them to Account Service,
                                and queues locally when Account is unavailable.
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("Event Ledger")));
    }
}
