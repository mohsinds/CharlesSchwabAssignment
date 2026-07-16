package com.schwab.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "account.allowed-negative-balance=false")
@AutoConfigureMockMvc
class NegativeBalanceGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsDebitThatWouldGoNegative() throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-neg-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-neg-debit-1",
                                  "type": "DEBIT",
                                  "amount": 50.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient funds")));
    }

    @Test
    void allowsDebitWhenFundsAreSufficient() throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-neg-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-neg-credit-1",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-neg-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-neg-debit-2",
                                  "type": "DEBIT",
                                  "amount": 40.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T15:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());
    }
}
