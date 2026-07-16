package com.schwab.eventledger.account;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Epic("Account Service")
@Feature("Negative balance guard")
@SpringBootTest(properties = "account.allowed-negative-balance=false")
@AutoConfigureMockMvc
class NegativeBalanceGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Story("Overdraft rejected")
    @DisplayName("DEBIT with insufficient funds returns 422")
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
    @Story("Funded debit allowed")
    @DisplayName("DEBIT succeeds when balance covers the amount")
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

    @Test
    @Story("CREDIT always allowed")
    @DisplayName("CREDIT is accepted even when NSF guard is enabled")
    void allowsCreditWhenNegativeBalanceDisallowed() throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-neg-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-neg-credit-only",
                                  "type": "CREDIT",
                                  "amount": 25.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());
    }
}
