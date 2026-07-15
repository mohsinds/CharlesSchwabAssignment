package com.schwab.eventledger.account.api;

import com.schwab.eventledger.account.service.AccountLedgerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountLedgerService accountLedgerService;

    public AccountController(AccountLedgerService accountLedgerService) {
        this.accountLedgerService = accountLedgerService;
    }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {
        log.info("Applying transaction eventId={} accountId={}", request.getEventId(), accountId);
        AccountLedgerService.ApplyResult result = accountLedgerService.applyTransaction(accountId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        log.info("Fetching balance accountId={}", accountId);
        return accountLedgerService.getBalance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        log.info("Fetching account accountId={}", accountId);
        return accountLedgerService.getAccount(accountId);
    }
}
