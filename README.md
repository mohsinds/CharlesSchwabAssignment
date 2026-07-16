# Event Ledger

Take-home for Charles Schwab: two independently runnable Spring Boot microservices that ingest financial transaction events with **idempotency**, **out-of-order balance correctness**, **distributed tracing**, **resiliency**, and **graceful degradation**.

Architecture and decisions: [`SPECS.md`](SPECS.md).  
Project knowledge (lifecycle, function index, scope): [`PROJECT.md`](PROJECT.md).  
Testing / CI / Allure / Sonar: [`TESTING_REPORT.md`](TESTING_REPORT.md).  
Diagnose: Cursor `/diagnose` or `bash .cursor/skills/diagnose-event-ledger/scripts/diagnose.sh`.

---

## Architecture overview

```text
Client
  │ REST
  ▼
event-gateway :8080
  RateLimiter → Validate → Idempotency / Outbox (H2)
       │
       │ Retry (exp backoff + jitter) → CircuitBreaker → Timeout
       │ REST + W3C traceparent
       ▼
account-service :8081
  Idempotent ledger (H2) → Balance = Σ CREDIT − Σ DEBIT

Observability (docker compose — also works with Maven/IDE apps):
  apps ──OTLP──► otel-collector ──► jaeger (:16686)
  apps ──HTTP──► loki (:3100)  ◄── grafana (:3000)
  prometheus (:9090) scrapes /actuator/prometheus (+ jaeger metrics)
  grafana datasources: Prometheus, Loki, Jaeger
  tags on metrics/logs/traces: service, env, namespace=event-ledger
```

- Services are separate JVMs with **separate H2 databases** (no shared state).
- Happy-path communication is **synchronous REST only** (assignment constraint).
- **No Kafka broker** in this solution. Bonus async fallback uses a **Gateway-local H2 outbox**.

### Event Gateway (`:8080`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Get event by id |
| `GET` | `/events?account={accountId}` | List events ordered by `eventTimestamp` |
| `GET` | `/accounts/{id}/balance` | Proxy balance query (503 if Account down) |
| `GET` | `/health` | Health + DB diagnostic |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### Account Service (`:8081`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply transaction (idempotent on `eventId`) |
| `GET` | `/accounts/{accountId}/balance` | Current balance |
| `GET` | `/accounts/{accountId}` | Account + recent transactions |
| `GET` | `/health` | Health + DB diagnostic |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

---

## Tech stack

| Concern | Choice |
|---------|--------|
| Language / framework | Java 21 + Spring Boot 3.4 |
| Build | Maven multi-module |
| DB | H2 in-memory (per service) |
| Inter-service | Sync REST (`RestClient`) |
| Resiliency | Resilience4j CircuitBreaker + Retry (exp backoff + jitter) + RateLimiter |
| Async fallback | Local H2 outbox + scheduled drain |
| Tracing | Micrometer Tracing → OTLP → OpenTelemetry Collector → Jaeger |
| Metrics | Micrometer + `/actuator/prometheus` (+ common tags) |
| Logs | JSON to console + Loki push (Grafana) |
| Dashboards | Grafana (Event Ledger Overview + Jaeger Monitor) |
| Contracts | Pact (consumer Gateway / provider Account) |
| Run | Docker Compose |

### Why these choices

- **Java/Spring** matches Schwab’s must-have stack for the role.
- **Sync REST** matches the problem constraints; messaging is the documented scale-up, not part of v1.
- **Circuit breaker** is the primary resiliency pattern: after repeated Account failures, fail fast into **202 + PENDING** (or **503** if async fallback is disabled) instead of exhausting Gateway threads.
- **Retry + jitter** absorbs blips without thundering herds; CB stops calling during sustained outage.
- **Ledger recompute** (`Σ CREDIT − Σ DEBIT`) keeps balances correct under out-of-order arrival.
- **Local outbox** satisfies the “queue events locally” bonus **without** standing up Kafka.

---

## Prerequisites

- JDK 21+
- Maven 3.9+
- Docker + Docker Compose (preferred run path)
- For CI quality: SonarCloud token in repo secret `SONAR_TOKEN`

---

## CI / quality

| Check | Detail |
|-------|--------|
| Workflow | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — **CI Pipeline** / **test-and-quality** |
| Coverage | JaCoCo line coverage ≥ **80%** (`mvn verify`) |
| Static analysis | SonarCloud project `mohsinds_CharlesSchwabAssignment` (gate wait in CI) |
| Test report | Allure → GitHub Pages on green `main` pushes |
| Full matrix | [`TESTING_REPORT.md`](TESTING_REPORT.md) |

```bash
mvn -B verify
```

---

## Setup

```bash
git clone <repository-url>
cd CharlesSchwabAssignment
mvn dependency:resolve
```

---

## Start both services

### Option A — Docker Compose (preferred)

```bash
docker compose up --build
```

| Service | URL |
|---------|-----|
| Event Gateway | http://localhost:8080 |
| Account Service | http://localhost:8081 |
| Gateway Swagger UI | http://localhost:8080/swagger-ui.html |
| Account Swagger UI | http://localhost:8081/swagger-ui.html |
| Gateway H2 console | http://localhost:8080/h2-console |
| Account H2 console | http://localhost:8081/h2-console |
| Grafana | http://localhost:3000 (admin / admin) |
| Jaeger UI | http://localhost:16686 |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |

Provisioned Grafana dashboards (folder **Event Ledger**):

- **Event Ledger Overview** — metrics + Loki logs (filter by `service` / `env` tags)
- **Jaeger Monitor** — Jaeger collector health + recent traces + correlated logs

```bash
docker compose down
```

### Option B — Local apps + observability stack

Run Grafana / Loki / Jaeger / Prometheus in Docker while the JVMs run on the host (Maven or IDE). Apps push logs to `localhost:3100` and traces to `localhost:4318` by default.

```bash
# Observability only (Prometheus scrapes host.docker.internal)
PROMETHEUS_CONFIG=./prometheus.local.yml docker compose up jaeger otel-collector prometheus loki grafana

# Terminal 1 — Account
mvn -pl account-service spring-boot:run

# Terminal 2 — Gateway
mvn -pl event-gateway spring-boot:run
```

Then open Grafana at http://localhost:3000 — same dashboards as the full Compose path.

### Option C — Apps only (no observability containers)

```bash
# Terminal 1
mvn -pl account-service spring-boot:run

# Terminal 2
mvn -pl event-gateway spring-boot:run
```

Logs still print JSON to the console; Loki push fails soft if Loki is not running.

### Smoke test

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
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
  }'
```

Prefer a browser? Use Swagger and inspect both H2 databases

---

## Run tests

```bash
mvn test
```

Coverage includes:

- Idempotency, out-of-order listing, balance math, validation
- Circuit breaker / Account failure behavior
- Retry with exponential backoff + jitter
- Rate limiting (`429`)
- Async outbox fallback (`202` → drain → `APPLIED`) and strict `503` mode
- Trace propagation (`traceparent` Gateway → Account)
- Prometheus custom series
- Pact consumer + provider verification
- Integration happy path

---

## Resiliency pattern choice

**Primary: Circuit breaker** (Resilience4j) on Gateway → Account calls, paired with:

- **Timeouts** on the HTTP client (connect 1s / read 2s)
- **Retry** with exponential backoff + randomized jitter (`GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS`, default 3; no retry on 4xx or open circuit)

**Why circuit breaker**

Upstream Account unavailability should not exhaust Gateway capacity. After a failure threshold, the circuit **opens**, subsequent calls fail fast, and the Gateway either:

- queues the event locally (`202 Accepted`, `status=PENDING`) when `gateway.async-fallback.enabled=true` (default), or
- returns `503 Service Unavailable` when async fallback is disabled

Bulkhead (thread-pool isolation) is a valid alternative; CB is clearer to demonstrate and test for this exercise size.

---

## Graceful degradation

| Call | Account unavailable |
|------|---------------------|
| `POST /events` (fallback on) | `202` + local `PENDING` outbox; drain when Account recovers |
| `POST /events` (fallback off) | `503` |
| `GET /events/{id}`, `GET /events?account=` | Still works from Gateway H2 |
| Balance / account proxy | `503` with clear message |

Toggle strict mode:

```bash
GATEWAY_ASYNC_FALLBACK_ENABLED=false mvn -pl event-gateway spring-boot:run
```

### Negative balance guard (Account)

Default allows negative balances (`account.allowed-negative-balance=true`). Disable to reject overdraft DEBITs with **422**:

```bash
ACCOUNT_ALLOWED_NEGATIVE_BALANCE=false mvn -pl account-service spring-boot:run
```

Gateway forwards Account’s 422 and message (e.g. `Insufficient funds: ...`). Outbox items that later hit a permanent 4xx become `REJECTED` (not retried forever).

### Configurable Account retry

Retry attempts on Gateway → Account (paired with the circuit breaker):

```bash
GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS=5 GATEWAY_ACCOUNT_RETRY_WAIT=200ms mvn -pl event-gateway spring-boot:run
```

### Graceful shutdown

Both services use `server.shutdown=graceful` (30s phase). Gateway pauses outbox drain on shutdown so in-flight HTTP finishes; PENDING rows remain until the process exits (H2 is in-memory for this exercise).

---

## Bonus features implemented

| Bonus | Implementation |
|-------|----------------|
| OTel Collector + Jaeger | `docker-compose.yml` (`otel-collector`, `jaeger`) + Grafana Jaeger Monitor |
| Prometheus metrics | `/actuator/prometheus` + custom counters/gauges + common tags |
| Grafana + Loki | Log push from both services; provisioned dashboards (local Maven + Compose) |
| Retry + exp backoff + jitter | Resilience4j Retry on Account client (`GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS`) |
| Rate limiting | Resilience4j RateLimiter on `POST /events` → `429` |
| Pact contracts | `pacts/event-gateway-account-service.json` + consumer/provider tests |
| Async local queue | H2 outbox + `@Scheduled` drain (`PENDING` / `APPLIED` / `REJECTED`) |

### What was intentionally not built

**Kafka / RabbitMQ broker.** The brief requires sync REST between services. The local outbox is the correct interpretation of the async-fallback bonus. A production evolution would publish that outbox to Kafka for fan-out / multi-consumer scale — described in `SPECS.md` for the walkthrough.

---

## Project layout

```text
.
├── TESTING_REPORT.md
├── SPECS.md
├── PROJECT.md
├── README.md
├── pom.xml
├── docker-compose.yml
├── .github/workflows/ci.yml
├── otel-collector-config.yaml
├── prometheus.yml
├── prometheus.local.yml
├── loki-config.yaml
├── grafana/
├── .cursor/rules/
├── .cursor/commands/
├── .cursor/skills/
├── pacts/
├── event-gateway/
└── account-service/
```

---

## Interview talking points (quick)

1. Spec-driven start (`SPECS.md`) before AI-assisted implementation — matches the role’s expectation.
2. Sync REST obeyed; Kafka deferred as an evolution of the outbox.
3. CB + Retry + jitter is the production pairing for transient vs sustained failure.
4. Balances from ledger aggregation, not arrival-order mutations.
5. Persist `APPLIED` only after Account success; `PENDING` outbox otherwise; Account idempotency makes drains safe.
