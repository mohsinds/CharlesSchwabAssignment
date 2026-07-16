package com.schwab.eventledger.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.eventledger.account.api.AccountResponse;
import com.schwab.eventledger.account.api.BalanceResponse;
import com.schwab.eventledger.account.api.TransactionRequest;
import com.schwab.eventledger.account.api.TransactionResponse;
import com.schwab.eventledger.account.config.AccountProperties;
import com.schwab.eventledger.account.domain.TransactionEntity;
import com.schwab.eventledger.account.domain.TransactionRepository;
import com.schwab.eventledger.account.domain.TransactionType;
import com.schwab.eventledger.account.metrics.TransactionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AccountLedgerService {

    private static final Logger log = LoggerFactory.getLogger(AccountLedgerService.class);
    private static final String DEFAULT_CURRENCY = "USD";

    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;
    private final TransactionMetrics transactionMetrics;
    private final AccountProperties accountProperties;

    public AccountLedgerService(
            TransactionRepository transactionRepository,
            ObjectMapper objectMapper,
            TransactionMetrics transactionMetrics,
            AccountProperties accountProperties) {
        this.transactionRepository = transactionRepository;
        this.objectMapper = objectMapper;
        this.transactionMetrics = transactionMetrics;
        this.accountProperties = accountProperties;
    }

    public record ApplyResult(TransactionResponse response, boolean created) {
    }

    @Transactional
    public ApplyResult applyTransaction(String accountId, TransactionRequest request) {
        Optional<TransactionEntity> existing = transactionRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            TransactionEntity txn = existing.get();
            if (!txn.getAccountId().equals(accountId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "eventId already applied to a different account");
            }
            log.info("Duplicate transaction ignored eventId={} accountId={}", request.getEventId(), accountId);
            transactionMetrics.recordApplied("duplicate");
            return new ApplyResult(toResponse(txn), false);
        }

        rejectIfNegativeBalanceDisallowed(accountId, request);

        TransactionEntity entity = new TransactionEntity();
        entity.setEventId(request.getEventId());
        entity.setAccountId(accountId);
        entity.setType(request.getType());
        entity.setAmount(request.getAmount());
        entity.setCurrency(request.getCurrency());
        entity.setEventTimestamp(request.getEventTimestamp());
        entity.setMetadataJson(writeMetadata(request.getMetadata()));
        entity.setAppliedAt(Instant.now());

        TransactionEntity saved = transactionRepository.save(entity);
        transactionMetrics.recordApplied("created");
        String eventId = saved.getEventId();
        String type = saved.getType().name();
        BigDecimal amount = saved.getAmount();
        log.info("Applied transaction eventId={} accountId={} type={} amount={}",
                eventId, accountId, type, amount);
        return new ApplyResult(toResponse(saved), true);
    }

    private void rejectIfNegativeBalanceDisallowed(String accountId, TransactionRequest request) {
        if (accountProperties.isAllowedNegativeBalance()) {
            return;
        }
        if (request.getType() != TransactionType.DEBIT) {
            return;
        }
        BigDecimal current = Optional.ofNullable(transactionRepository.computeBalance(accountId))
                .orElse(BigDecimal.ZERO);
        BigDecimal projected = current.subtract(request.getAmount());
        if (projected.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Rejected debit that would go negative eventId={} accountId={} balance={} amount={}",
                    request.getEventId(), accountId, current, request.getAmount());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient funds: debit would result in negative balance (allowed-negative-balance=false)");
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        ensureAccountExists(accountId);
        BigDecimal balance = transactionRepository.computeBalance(accountId);
        String currency = resolveCurrency(accountId);
        return new BalanceResponse(accountId, balance, currency);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        ensureAccountExists(accountId);
        BigDecimal balance = transactionRepository.computeBalance(accountId);
        String currency = resolveCurrency(accountId);
        List<TransactionResponse> recent = transactionRepository
                .findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
        return new AccountResponse(accountId, balance, currency, recent);
    }

    private void ensureAccountExists(String accountId) {
        if (!transactionRepository.existsByAccountId(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId);
        }
    }

    private String resolveCurrency(String accountId) {
        return transactionRepository.findTop20ByAccountIdOrderByEventTimestampDescEventIdDesc(accountId)
                .stream()
                .findFirst()
                .map(TransactionEntity::getCurrency)
                .orElse(DEFAULT_CURRENCY);
    }

    private TransactionResponse toResponse(TransactionEntity entity) {
        return new TransactionResponse(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getEventTimestamp(),
                readMetadata(entity.getMetadataJson()),
                entity.getAppliedAt()
        );
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata");
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}
