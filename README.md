# Event Ledger

Take-home exercise for Charles Schwab: a two-microservice **Event Ledger** that accepts financial transaction events, applies them safely under out-of-order and duplicate delivery, and stays observable and resilient when dependencies fail.

---

## Architecture overview

```text
┌──────────────────────┐
│   Browser / Client   │
└──────────┬───────────┘
           │  REST (sync)
           ▼
┌────────────────────────┐
│  Event Gateway API     │  public-facing
│  · validate events     │
│  · enforce idempotency │
│  · store event records │
│  · propagate tracing   │
└──────────┬─────────────┘
           │  REST (sync) + trace headers
           ▼
┌──────────────────────┐
│   Account Service    │  internal only
│  · apply transactions│
│  · compute balances  │
│  · account history   │
└──────────────────────┘
```

### Event Gateway API

Public entry point for clients. Responsibilities:

- Accept and validate transaction events (`POST /events`)
- Enforce **idempotency** by `eventId` (duplicates return the original event)
- Persist event records in its **own** embedded/in-memory database
- Call the Account Service to apply balance-affecting transactions
- Serve event lookups that work even when Account Service is down
- Generate and propagate a **trace ID** on every inbound request
- Apply a **resiliency pattern** on the Account Service client call

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event by ID |
| `GET` | `/events?account={accountId}` | List events for an account (ordered by `eventTimestamp`) |
| `GET` | `/health` | Health check (incl. DB diagnostics) |

### Account Service

Internal service that owns account balances and transaction application. Not exposed to external clients. Responsibilities:

- Apply CREDIT / DEBIT transactions to account state
- Compute **net balance** = Σ CREDITS − Σ DEBITS (correct regardless of arrival order)
- Expose account details and recent transactions
- Log the propagated **trace ID** on every request

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction to an account |
| `GET` | `/accounts/{accountId}/balance` | Get current balance |
| `GET` | `/accounts/{accountId}` | Get account details and recent transactions |
| `GET` | `/health` | Health check (incl. DB diagnostics) |

### Design constraints

- Services are **independently runnable** processes
- Each service has its **own** embedded/in-memory database (no shared DB or in-process state)
- Communication is **synchronous REST** only
- Out-of-order events are tolerated; listings are chronological by `eventTimestamp`
- Duplicate `eventId` submissions must not change balance or create duplicate records

---

## Event payload

Submitted to `POST /events` on the Gateway:

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source": "mainframe-batch",
    "batchId": "B-9042"
  }
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `eventId` | string | Yes | Unique event identifier (idempotency key) |
| `accountId` | string | Yes | Account the event belongs to |
| `type` | string | Yes | `"CREDIT"` or `"DEBIT"` |
| `amount` | number | Yes | Must be &gt; 0 |
| `currency` | string | Yes | e.g. `"USD"` |
| `eventTimestamp` | string (ISO 8601) | Yes | When the event originally occurred |
| `metadata` | object | No | Optional context |

---

## Tech stack

| Concern | Choice |
|---------|--------|
| Language / framework | Java + Spring Boot |
| Databases | Embedded H2 per service |
| Inter-service calls | Synchronous REST (Spring WebClient / RestClient) |
| Tracing | Trace ID generation + HTTP header propagation; structured JSON logs |
| Resilience | Circuit breaker on Gateway → Account Service calls (see below) |
| Packaging / run | Docker Compose (preferred) |
| Tests | JUnit / Spring Boot Test (`mvn test`) |

---

## Prerequisites

- **JDK 21+** (or the version pinned in the project)
- **Maven 3.9+**
- **Docker** and **Docker Compose** (for the preferred run path)
- Optional: `curl` or an API client for manual smoke tests

---

## Setup / install dependencies

```bash
git clone <repository-url>
cd CharlesSchwabAssignment

# Resolve and download dependencies for both modules
mvn dependency:resolve
```

If the repo uses a multi-module layout (e.g. `event-gateway` and `account-service`):

```bash
mvn -pl event-gateway,account-service dependency:resolve
```

---

## How to start both services

### Option A — Docker Compose (preferred)

```bash
docker compose up --build
```

Typical local ports (confirm in `docker-compose.yml` / application configs):

| Service | URL |
|---------|-----|
| Event Gateway | `http://localhost:8080` |
| Account Service | `http://localhost:8081` |

Stop with:

```bash
docker compose down
```

### Option B — Manual (local JVMs)

Start Account Service first, then the Gateway:

```bash
# Terminal 1 — Account Service
cd account-service
mvn spring-boot:run

# Terminal 2 — Event Gateway
cd event-gateway
mvn spring-boot:run
```

Configure the Gateway’s Account Service base URL via environment / `application.yml` (e.g. `http://localhost:8081`).

### Quick smoke checks

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health

curl -s -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z"
  }'
```

---

## How to run the tests

From the repository root:

```bash
mvn test
```

Test coverage expected by this exercise includes:

- **Core behavior** — idempotency, out-of-order arrival, balance math, input validation
- **Resiliency** — Account Service failure simulation; Gateway returns appropriate errors (e.g. `503`) without hanging
- **Trace propagation** — trace ID flows Gateway → Account Service and appears in structured logs
- **Integration** — at least one end-to-end Gateway → Account Service flow

---

## Observability

- **Structured logging** — JSON logs with timestamp, level, service name, and **trace ID**
- **Health** — `GET /health` on both services (status + basic DB connectivity)
- **Metrics** — at least one custom metric (request counts, errors, and/or latency) exposed via logs, actuator, or a metrics endpoint
- **Tracing** — Gateway generates a trace ID per request and propagates it to Account Service over HTTP headers so a single client call is correlatable across both services

---

## Graceful degradation

When Account Service is unavailable:

| Endpoint | Behavior |
|----------|----------|
| `POST /events` | Fail fast with a clear error (e.g. `503 Service Unavailable`) — do not hang or return a generic `500` |
| `GET /events/{id}`, `GET /events?account=...` | Continue to work from Gateway-local data |
| Balance / account queries that need Account Service | Return a clear unreachable/unavailable error |

---

## Resiliency pattern choice

**Circuit breaker** on the Gateway’s call to the Account Service.

**Why this pattern**

- Upstream financial feeds can spike load or leave Account Service briefly unhealthy. A circuit breaker **fails fast** after repeated failures instead of exhausting Gateway threads on doomed retries.
- Clients get a deterministic, meaningful response (`503` / degraded) instead of long timeouts.
- Pairs naturally with health checks and graceful degradation: read paths that only need Gateway state stay up while write paths open the circuit.

**Intended behavior (summary)**

1. Normal operation — calls Account Service on every accepted new event.
2. After a threshold of failures/timeouts — circuit **opens**; subsequent calls short-circuit without hitting Account Service.
3. After a cool-down — circuit moves to **half-open**, probes with limited traffic, then closes on success or re-opens on failure.

Timeout + retry (with bounded backoff) and/or bulkheads are reasonable complements; the required “at least one” pattern documented and tested here is the circuit breaker.

---

## Project layout (expected)

```text
.
├── README.md
├── docker-compose.yml
├── event-gateway/          # public API, idempotency, resiliency client
├── account-service/        # balances & account state
└── ...
```

Exact module names may vary; see the root Maven / Docker Compose files for the source of truth.

---

## Submission notes

- Solution is delivered as a **Git repository** with a commit history that reflects the working process.
- AI coding assistants are expected and encouraged for this exercise; engineering judgment and the quality of the result are what matter.
