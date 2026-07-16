package com.schwab.eventledger.account.api;

import com.schwab.eventledger.account.service.AccountLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Accounts", description = "Apply transactions and query balances")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountLedgerService accountLedgerService;

    public AccountController(AccountLedgerService accountLedgerService) {
        this.accountLedgerService = accountLedgerService;
    }

    @PostMapping("/{accountId}/transactions")
    @Operation(summary = "Apply a transaction",
            description = "Idempotent on eventId. Creates the account implicitly on first transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction applied"),
            @ApiResponse(responseCode = "200", description = "Duplicate eventId — original response returned"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {
        log.info("Applying transaction eventId={} accountId={}", request.getEventId(), accountId);
        AccountLedgerService.ApplyResult result = accountLedgerService.applyTransaction(accountId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get account balance",
            description = "Balance = SUM(CREDIT amounts) − SUM(DEBIT amounts)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance returned"),
            @ApiResponse(responseCode = "404", description = "Account has no transactions")
    })
    public BalanceResponse getBalance(@PathVariable String accountId) {
        log.info("Fetching balance accountId={}", accountId);
        return accountLedgerService.getBalance(accountId);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get account detail",
            description = "Balance plus up to 20 most recent transactions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "404", description = "Account has no transactions")
    })
    public AccountResponse getAccount(@PathVariable String accountId) {
        log.info("Fetching account accountId={}", accountId);
        return accountLedgerService.getAccount(accountId);
    }
}
