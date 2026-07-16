package com.schwab.eventledger.account.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Account Service API")
                        .description("""
                                Internal ledger API for the Event Ledger.
                                Applies transactions idempotently and computes balances as
                                SUM(CREDIT) − SUM(DEBIT).
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("Event Ledger")));
    }
}
