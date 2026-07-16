package com.schwab.eventledger.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "account")
public class AccountProperties {

    /**
     * When false, DEBIT that would make ledger balance &lt; 0 is rejected with HTTP 422.
     * Default true preserves out-of-order debit-before-credit demos from the problem statement.
     */
    private boolean allowedNegativeBalance = true;

    public boolean isAllowedNegativeBalance() {
        return allowedNegativeBalance;
    }

    public void setAllowedNegativeBalance(boolean allowedNegativeBalance) {
        this.allowedNegativeBalance = allowedNegativeBalance;
    }
}
