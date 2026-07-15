package com.schwab.eventledger.account.api;

import java.math.BigDecimal;
import java.util.List;

public record AccountResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        List<TransactionResponse> recentTransactions
) {
}
