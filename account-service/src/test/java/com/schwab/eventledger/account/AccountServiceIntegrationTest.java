package com.schwab.eventledger.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appliesTransactionsIdempotentlyAndHandlesOutOfOrderBalance() throws Exception {
        String accountId = "acct-order-1";

        postTxn(accountId, "evt-later", "CREDIT", "100.00", "2026-05-15T15:00:00Z")
                .andExpect(status().isCreated());

        postTxn(accountId, "evt-earlier", "DEBIT", "40.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        // duplicate must not change balance
        postTxn(accountId, "evt-later", "CREDIT", "100.00", "2026-05-15T15:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-later"));

        MvcResult balance = mockMvc.perform(get("/accounts/{accountId}/balance", accountId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(balance.getResponse().getContentAsString());
        assertThat(new BigDecimal(node.get("balance").asText())).isEqualByComparingTo("60.00");

        mockMvc.perform(get("/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentTransactions.length()").value(2));
    }

    @Test
    void rejectsInvalidAmountAndUnknownType() throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-bad-1",
                                  "type": "CREDIT",
                                  "amount": 0,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-bad-2",
                                  "type": "TRANSFER",
                                  "amount": 10,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthReportsDatabaseUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.service").value("account-service"));
    }

    @Test
    void prometheusExposesCustomTransactionMetric() throws Exception {
        postTxn("acct-metric", "evt-metric-1", "CREDIT", "10.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("transactions_applied_total"));
    }

    private org.springframework.test.web.servlet.ResultActions postTxn(
            String accountId, String eventId, String type, String amount, String ts) throws Exception {
        String body = """
                {
                  "eventId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s",
                  "metadata": {"source": "test"}
                }
                """.formatted(eventId, type, amount, ts);
        return mockMvc.perform(post("/accounts/{accountId}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
