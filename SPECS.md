# Event Ledger — Architecture Specification

Spec-driven design artifact for the Charles Schwab take-home exercise. This document locks stack choices, API contracts, algorithms, resiliency, observability, and bonus scope before implementation.

---

## 1. Problem summary

Build an **Event Ledger** of two independently runnable microservices:

| Service | Role |
|---------|------|
| **Event Gateway** (`:8080`) | Public API: validate, idempotent ingest, store events, call Account Service |
| **Account Service** (`:8081`) | Internal API: apply transactions, compute balances, account queries |

Upstream systems may deliver events **out of order** and **more than once**. The system must remain correct, observable, and resilient when the Account Service is unavailable.

### Non-goals

- **No Kafka / RabbitMQ broker** as a runtime dependency. Inter-service communication is **synchronous REST**. The bonus async fallback uses a **Gateway-local H2 outbox**, not a message bus.
- No shared database or shared in-process state between services.
- No authentication / authorization (out of exercise scope).
- H2 is **in-memory** (assignment isolation); graceful shutdown preserves in-flight requests within a process lifetime, not cross-restart durability.

See also [`PROJECT.md`](PROJECT.md) for the full in/out-of-scope matrix and function index.

### Production evolution (interview note)

A natural scale-up is: Gateway outbox → **Kafka** → Account consumer, while keeping per-`eventId` idempotency. That maps messaging experience to the role’s nice-to-have skills without violating this exercise’s constraints.

---

## 2. Tech stack (locked)

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Java 21 | Matches Schwab JD must-have |
| Framework | Spring Boot 3.4.x | Spring ecosystem; production-aligned |
| Build | Maven multi-module | `mvn test` from root |
| Happy-path comms | Sync REST (`RestClient`) | Hard constraint |
| Async fallback | H2 outbox + `@Scheduled` drain | Bonus: “queue events locally” |
| DB (each service) | H2 in-memory | Required isolation |
| Resiliency | Resilience4j CB + timeout + Retry (exp backoff + jitter) | Required + bonus |
| Rate limit | Resilience4j RateLimiter on `POST /events` | Bonus |
| Tracing | Micrometer Tracing → OTLP → OpenTelemetry Collector → Jaeger | Required + bonus |
| Metrics | Micrometer + `/actuator/prometheus` | Required custom metric + bonus |
| Contracts | Pact JVM (consumer Gateway, provider Account) | Bonus |
| Run | Docker Compose | Preferred |
| Tests | JUnit 5, MockMvc, WireMock, Pact, AssertJ | Full matrix |

---

## 3. Architecture

```text
Client
  │ REST
  ▼
event-gateway :8080
  RateLimiter → Validate → Idempotency/Outbox (H2)
       │
       │ Retry → CircuitBreaker → Timeout
       │ REST + W3C traceparent
       ▼
account-service :8081
  Idempotent ledger (H2) → Balance = Σ CREDIT − Σ DEBIT

Observability (Compose):
  both apps ──OTLP──► otel-collector ──► jaeger
  prometheus scrapes /actuator/prometheus on both
```

---

## 4. API contracts

### 4.1 Event Gateway

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/events` | Submit event |
| `GET` | `/events/{id}` | Get event by id |
| `GET` | `/events?account={accountId}` | List by account, ordered by `eventTimestamp` ASC, then `eventId` |
| `GET` | `/health` | Liveness + DB diagnostic |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

#### `POST /events` request body

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```

Validation: required fields present; `type` ∈ {CREDIT, DEBIT}; `amount` > 0.

#### Response statuses

| Case | Status |
|------|--------|
| Applied immediately | `201 Created` |
| Duplicate already applied | `200 OK` + original body |
| Queued (async fallback) | `202 Accepted` (`status=PENDING`) |
| Duplicate while still PENDING | `202 Accepted` + current body |
| Rate limited | `429 Too Many Requests` |
| Validation error | `400 Bad Request` |
| Async fallback disabled + Account down | `503 Service Unavailable` |
| Not found | `404 Not Found` |

#### Event response body (Gateway)

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {},
  "status": "APPLIED",
  "createdAt": "2026-05-15T14:05:00Z"
}
```

`status`: `APPLIED` | `PENDING`.

### 4.2 Account Service

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply transaction (idempotent on `eventId`) |
| `GET` | `/accounts/{accountId}/balance` | Current balance |
| `GET` | `/accounts/{accountId}` | Account + recent transactions |
| `GET` | `/health` | Liveness + DB diagnostic |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

#### Apply transaction body

Same business fields as the event (including `eventId`). Duplicate `eventId` returns the original applied transaction with `200`.

Balance formula: `SUM(CREDIT amounts) − SUM(DEBIT amounts)` for the account. **Never** mutate balance solely by arrival order.

---

## 5. Data models

### Gateway `events` (H2)

| Column | Notes |
|--------|-------|
| `event_id` PK | Idempotency key |
| `account_id` | Indexed |
| `type`, `amount`, `currency`, `event_timestamp` | Business fields |
| `metadata_json` | Optional JSON |
| `status` | `PENDING` / `APPLIED` |
| `created_at` | Ingest time |
| `attempt_count` | Outbox drain attempts |

### Account `transactions` (H2)

| Column | Notes |
|--------|-------|
| `event_id` PK | Idempotency key |
| `account_id` | Indexed |
| `type`, `amount`, `currency`, `event_timestamp` | Business fields |
| `metadata_json` | Optional |
| `applied_at` | When applied |

Accounts are implicit: created/derived from first successful transaction.

---

## 6. Core algorithms

### 6.1 Write path (`POST /events`)

```text
1. RateLimiter.acquire → else 429
2. Validate → else 400
3. If eventId exists:
     APPLIED → 200 + body
     PENDING → 202 + body
     REJECTED → 422
4. Call Account via Retry → CB → Timeout
     success → persist APPLIED → 201
     4xx → map same status + Account message, do not persist (e.g. 422 NSF)
     unavailable / CB open / timeout:
       if async-fallback.enabled → persist PENDING → 202
       else → 503
```

### 6.2 Outbox drain

- Interval ~2s
- Skip while circuit is OPEN or Gateway is shutting down
- Process PENDING oldest-first
- Account apply is idempotent → safe redelivery
- On success → `APPLIED`
- On permanent Account **4xx** → `REJECTED` (terminal; no infinite retry)
- On transient failure → increment `attempt_count`, remain `PENDING`

### 6.3 Out-of-order

- Listings: `ORDER BY event_timestamp ASC, event_id ASC`
- Balance: ledger aggregation, not arrival-order running total

### 6.4 Negative balance guard (Account)

- Config: `account.allowed-negative-balance` / `ACCOUNT_ALLOWED_NEGATIVE_BALANCE` (default **true**)
- When **false**, a DEBIT whose projected balance (`current − amount`) is &lt; 0 → **422 Unprocessable Entity** with message containing `Insufficient funds`
- Gateway forwards that status and message on sync submit
- Caveat: arrival-order NSF check can reject a debit-before-credit that would be valid after event-time reordering

---

## 7. Resiliency configuration

| Component | Settings (defaults) |
|-----------|---------------------|
| RestClient timeout | connect 1s, read 2s |
| Retry | `maxAttempts` = `GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS` (default 3); wait = `GATEWAY_ACCOUNT_RETRY_WAIT` (default 200ms); exponential backoff + randomized jitter; retry IO/5xx/timeout only |
| CircuitBreaker | failureRateThreshold=50%, slidingWindowSize=10, waitDurationInOpenState=10s |
| RateLimiter | limitForPeriod=20, limitRefreshPeriod=1s on `POST /events` |
| Async fallback | `gateway.async-fallback.enabled=true` (default) |
| Graceful shutdown | `server.shutdown=graceful`; `spring.lifecycle.timeout-per-shutdown-phase=30s`; Gateway pauses outbox drain |

**Why circuit breaker (primary interview answer):** After repeated Account failures, stop calling and fail fast (into 202 queue or 503) so Gateway threads are not exhausted. Retry handles blips; CB handles sustained outage; jitter reduces thundering herd.

Bulkhead is a valid alternative (thread-pool isolation) but is not the primary pattern implemented.

---

## 8. Observability contracts

### Logs (JSON)

Fields: `timestamp`, `level`, `service`, `env`, `namespace`, `traceId`, `spanId`, `message` (+ optional `logger`).

Pushed to **Loki** with labels `service`, `env`, `namespace=event-ledger` for Grafana (works for Compose and local Maven).

### Tracing

- Gateway creates root span per request
- Propagate W3C `traceparent` to Account Service
- Export OTLP → OpenTelemetry Collector → Jaeger
- Resource tags: `service.namespace=event-ledger`, `deployment.environment`

### Metrics (custom)

- `events_submitted_total{result=...}` — created | duplicate | rejected | queued | rate_limited
- `account_service_call_duration_seconds`
- `outbox_pending_events` (gauge)
- `outbox_drain_success_total`
- Common tags on all series: `application`, `service`, `env`, `namespace`

### Grafana

- Datasources: Prometheus, Loki, Jaeger
- Dashboards: Event Ledger Overview, Jaeger Monitor (`grafana/dashboards/`)

### Health

`GET /health` → `{ "status": "UP", "database": "UP", "service": "..." }`

---

## 9. Pact contracts

| Side | Module | Interactions |
|------|--------|--------------|
| Consumer | `event-gateway` | `POST /accounts/{accountId}/transactions`, `GET .../balance` |
| Provider | `account-service` | Verifies against `pacts/*.json` |

Broker-less; pacts committed under `pacts/`.

---

## 10. Repository layout

```text
.
├── SPECS.md
├── PROJECT.md
├── README.md
├── pom.xml
├── docker-compose.yml
├── otel-collector-config.yaml
├── prometheus.yml
├── prometheus.local.yml
├── loki-config.yaml
├── grafana/
├── .cursor/
├── pacts/
├── event-gateway/
└── account-service/
```

---

## 11. Test matrix

| Case | Verification |
|------|----------------|
| Idempotency | Double POST → 200; balance unchanged |
| Out-of-order | List order + balance math |
| Validation | 400 for bad payloads |
| Circuit breaker | Failures → OPEN → fail-fast 202/503 |
| Retry + jitter | Configurable `maxAttempts`; 4xx not retried |
| Rate limit | Burst → 429 |
| Async fallback | Account down → 202 PENDING → drain → APPLIED |
| Outbox reject | Permanent Account 4xx → `REJECTED` |
| NSF guard | `allowed-negative-balance=false` → 422 |
| Graceful shutdown | Outbox drain paused; Tomcat graceful |
| Strict 503 mode | `async-fallback=false` → 503; GETs still work |
| Trace propagation | Same traceId across hop |
| Prometheus | Custom series present |
| Pact | Consumer + provider verify |
| Integration | Full Gateway → Account flow |
| CI quality | JaCoCo ≥ 80%, SonarCloud gate, Allure on Pages |

Command: `mvn verify` (see [`TESTING_REPORT.md`](TESTING_REPORT.md))

---

## 12. Interview talking points

1. Java/Spring chosen to match Schwab’s core stack.
2. No Kafka broker here because the brief mandates sync REST; local outbox satisfies the bonus and is the staging ground for a future Kafka topology.
3. CB + Retry + jitter is the production pairing: absorb blips, cut off sustained failure, avoid synchronized retries.
4. 202 + PENDING upgrades fail-fast 503 when durable ingest is preferred; flag restores strict degradation.
5. Balance via ledger recompute respects business time over arrival time.
6. Persist-after-Account-success (or PENDING outbox) avoids phantom APPLIED rows; Account idempotency makes drains safe.
7. Spec-driven development (`SPECS.md`) + AI-assisted implementation with human ownership of correctness.
