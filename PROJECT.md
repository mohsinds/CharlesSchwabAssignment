# Event Ledger — Project Knowledge Base

Canonical operator/agent reference for architecture, scope, request lifecycle, and key symbols.  
Locked design decisions live in [`SPECS.md`](SPECS.md). Runbook: [`README.md`](README.md).  
Testing / CI: [`TESTING_REPORT.md`](TESTING_REPORT.md).  
Diagnose: Cursor command `/diagnose` or skill `diagnose-event-ledger`.

---

## 1. Scope

### In scope

- Two independently runnable Spring Boot 3.4 / Java 21 services: **event-gateway** (`:8080`) and **account-service** (`:8081`)
- Sync REST Gateway → Account; idempotent ingest; out-of-order listing; balance = Σ CREDIT − Σ DEBIT
- Resiliency: CircuitBreaker + Retry (configurable attempts) + RateLimiter + timeouts
- Async fallback: Gateway-local H2 outbox (`PENDING` → drain → `APPLIED` / `REJECTED`)
- Observability: JSON logs (+ Loki), OTLP → Jaeger, Micrometer → Prometheus/Grafana
- Config toggles: async fallback, **allowed-negative-balance**, retry max attempts, graceful shutdown
- Pact contracts under `pacts/`

### Out of scope (v1)

- Kafka / RabbitMQ / any message broker
- Shared DB or shared in-process state between services
- AuthN / AuthZ
- Durable disk DB (H2 is in-memory; process exit loses data)
- Multi-region, multi-tenant SaaS concerns
- Changing money with FX / multi-currency math beyond storing `currency`

---

## 2. Services & features

| Feature | Gateway | Account |
|---------|---------|---------|
| `POST` ingest | `/events` | `/accounts/{id}/transactions` |
| Idempotency on `eventId` | Yes (store + short-circuit) | Yes (PK on `eventId`) |
| List by account / timestamp | Yes | Recent txns on account GET |
| Balance | Proxy to Account | Ledger recompute |
| Rate limit | `POST /events` | — |
| CB + Retry on Account calls | Yes | — |
| Outbox when Account down | Yes (`PENDING`) | — |
| Negative-balance guard | Forwards Account 422 | `account.allowed-negative-balance` |
| Graceful shutdown | Tomcat drain + pause outbox | Tomcat drain |
| Metrics / traces / logs | Yes | Yes |

---

## 3. Request lifecycle (`POST /events`)

```text
Client
  │ POST /events + JSON
  ▼
EventController.submit
  │ @Valid EventRequest
  ▼
EventService.submit  (@RateLimiter postEvents)
  ├─ duplicate eventId?
  │    PENDING  → 202 + body
  │    APPLIED  → 200 + body
  │    REJECTED → 422
  ├─ AccountServiceClient.applyTransaction
  │    @CircuitBreaker(accountService) + @Retry(accountService)
  │    RestClient → Account POST /accounts/{id}/transactions
  │         + W3C traceparent
  ├─ success → persist APPLIED → 201
  ├─ Account 4xx → no persist → same status + Account `message` (e.g. 422 insufficient funds)
  └─ 5xx / timeout / CB open
       async-fallback on  → persist PENDING → 202
       async-fallback off → 503

Background (Gateway):
  OutboxDrainService.drain @Scheduled
    skip if shutting down or CB OPEN
    PENDING → Account apply
      success → APPLIED
      4xx     → REJECTED (terminal)
      other   → stay PENDING, attemptCount++
```

Account apply path:

```text
AccountController.applyTransaction
  → AccountLedgerService.applyTransaction
       duplicate eventId → 200
       if DEBIT && !allowedNegativeBalance && balance - amount < 0 → 422
       else save ledger row → 201
```

---

## 4. Configuration reference

| Key / env | Default | Meaning |
|-----------|---------|---------|
| `account.allowed-negative-balance` / `ACCOUNT_ALLOWED_NEGATIVE_BALANCE` | `true` | When `false`, overdraft DEBIT → **422** |
| `gateway.async-fallback.enabled` / `GATEWAY_ASYNC_FALLBACK_ENABLED` | `true` | Queue vs hard 503 |
| `GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS` | `3` | Resilience4j Retry `maxAttempts` on Account calls |
| `GATEWAY_ACCOUNT_RETRY_WAIT` | `200ms` | Retry base wait |
| `server.shutdown` | `graceful` | Drain Tomcat before exit |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | Max wait for in-flight work |
| `LOKI_URL` | `http://localhost:3100/loki/api/v1/push` | Log push |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4318/v1/traces` | Traces |

**Note:** With `allowed-negative-balance=false`, a DEBIT that arrives before its funding CREDIT is rejected at apply time (arrival-order check), which can conflict with pure event-time reordering demos.

---

## 5. Function / type index (main)

### event-gateway

| Symbol | Path | Role |
|--------|------|------|
| `EventGatewayApplication` | `.../gateway/EventGatewayApplication.java` | Boot + `@EnableScheduling` |
| `EventController` | `.../api/EventController.java` | Public events API |
| `AccountProxyController` | `.../api/AccountProxyController.java` | Balance/account proxy |
| `HealthController` | `.../api/HealthController.java` | `/health` |
| `RestExceptionHandler` | `.../api/RestExceptionHandler.java` | 400/429/503/`ResponseStatusException` |
| `EventService.submit` | `.../service/EventService.java` | Idempotency, Account call, persist |
| `OutboxDrainService.drain` | `.../service/OutboxDrainService.java` | PENDING → APPLIED/REJECTED |
| `AccountServiceClient.applyTransaction` | `.../client/AccountServiceClient.java` | CB + Retry REST |
| `GatewayProperties` | `.../config/GatewayProperties.java` | Typed gateway config |
| `GracefulShutdownGuard` | `.../config/GracefulShutdownGuard.java` | Shutdown flag for outbox |
| `EventMetrics` | `.../metrics/EventMetrics.java` | Custom counters/gauges |
| `EventStatus` | `.../domain/EventStatus.java` | `PENDING` \| `APPLIED` \| `REJECTED` |

### account-service

| Symbol | Path | Role |
|--------|------|------|
| `AccountServiceApplication` | `.../account/AccountServiceApplication.java` | Boot + `AccountProperties` |
| `AccountController` | `.../api/AccountController.java` | Transactions / balance / account |
| `AccountLedgerService.applyTransaction` | `.../service/AccountLedgerService.java` | Ledger write + NSF guard |
| `TransactionRepository.computeBalance` | `.../domain/TransactionRepository.java` | Σ CREDIT − Σ DEBIT |
| `AccountProperties` | `.../config/AccountProperties.java` | `allowedNegativeBalance` |
| `GracefulShutdownGuard` | `.../config/GracefulShutdownGuard.java` | Shutdown marker |
| `TransactionMetrics` | `.../metrics/TransactionMetrics.java` | `transactions_applied_total` |

---

## 6. HTTP status cheat sheet

| Situation | Status |
|-----------|--------|
| Created applied event | 201 |
| Duplicate applied | 200 |
| Queued (Account down, fallback on) | 202 |
| Validation | 400 |
| Rate limited | 429 |
| Insufficient funds (NSF flag off) | **422** |
| eventId on wrong account (Account) | 409 |
| Account / event missing | 404 |
| Account down, fallback off / CB open | 503 |

---

## 7. Observability

- Logs: console JSON + Loki labels `service`, `env`, `namespace=event-ledger`
- Traces: Micrometer → OTLP collector → Jaeger; tags `service.namespace`, `deployment.environment`
- Metrics: `/actuator/prometheus`; Grafana dashboards under `grafana/dashboards/`
- Local apps + obs: `PROMETHEUS_CONFIG=./prometheus.local.yml docker compose up jaeger otel-collector prometheus loki grafana`

---

## 8. Doc maintenance

Cursor rule `.cursor/rules/update-docs.mdc` requires updating **README.md**, **SPECS.md**, and this file whenever behavior/config/API/scope changes.

## 9. Quality bar

- `mvn verify` — tests + JaCoCo ≥ 80% line coverage
- CI: `.github/workflows/ci.yml` job **test-and-quality** (SonarCloud gate + Allure → GitHub Pages)
- Details: [`TESTING_REPORT.md`](TESTING_REPORT.md)
