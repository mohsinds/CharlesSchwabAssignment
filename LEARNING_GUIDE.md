# Event Ledger — Learning Guide

A plain-language walkthrough of this project so you can explain every part in the interview.

Read this in order the first time. Later, use the file map as a cheat sheet.

---

## 1. What problem does this solve?

Imagine many upstream systems (mainframe batch, online trading, etc.) send **financial events** like:

> “Credit $150 to account acct-123”  
> “Debit $40 from account acct-123”

Those systems are messy:

1. **Duplicates** — the same event can arrive twice  
2. **Out of order** — Tuesday’s debit can arrive before Monday’s credit  
3. **Dependencies fail** — the service that owns balances can go down  

Your job is an **Event Ledger**: accept events safely, apply them to accounts, and stay correct and observable when things go wrong.

---

## 2. Big picture (two apps, not one)

```text
                    PUBLIC                         INTERNAL
┌──────────┐     ┌─────────────────────┐      ┌─────────────────────┐
│  Client  │────►│  Event Gateway      │─────►│  Account Service    │
│ (curl)   │     │  port 8080          │ REST │  port 8081          │
└──────────┘     │  “front door”       │      │  “ledger / balance” │
                 │  own H2 database    │      │  own H2 database    │
                 └─────────────────────┘      └─────────────────────┘
```

| App | Role in one sentence |
|-----|----------------------|
| **Event Gateway** | Public API: validate, stop duplicates, remember events, call Account Service |
| **Account Service** | Internal only: store transactions, compute balances |

Why two apps?

- Assignment requires **two microservices** that don’t share a database or memory  
- Shows you understand **service boundaries** (Gateway = edge; Account = domain truth for money)  
- Lets you practice **resiliency** when the second service is down  

**Important:** They talk only with **HTTP REST**. There is **no Kafka** in this project (on purpose).

---

## 3. Concepts you must understand before reading code

### 3.1 Idempotency

Idempotent means: **doing the same request twice has the same effect as doing it once.**

Key: `eventId` (e.g. `evt-001`).

| First `POST` with `evt-001` | Second `POST` with same `evt-001` |
|-----------------------------|-----------------------------------|
| Creates event, applies to balance | Returns original result; **does not** add money again |

Both services enforce this with a **primary key / unique `eventId`**.

### 3.2 Out-of-order events

Events have `eventTimestamp` = when the business event *happened* (not when it *arrived*).

Listings on the Gateway sort by `eventTimestamp` (business time).

Balances are **not** “running total in arrival order.” They are:

```text
balance = sum(all CREDIT amounts) − sum(all DEBIT amounts)
```

So arrival order cannot corrupt money math.

### 3.3 Circuit breaker (resiliency)

Think of an electrical breaker:

1. **Closed** — calls Account Service normally  
2. **Open** — after many failures: stop calling for a while (fail fast)  
3. **Half-open** — probe Account again; on success close, on failure open again  

File that wraps the HTTP call: `AccountServiceClient.java` (`@CircuitBreaker`).

### 3.4 Async fallback / outbox

When Account is down and fallback is **on**:

1. Gateway saves the event as `PENDING` in its **own** H2 DB  
2. Returns `202 Accepted` to the client  
3. A background job (`OutboxDrainService`) retries Account later  
4. On success, marks the event `APPLIED`  

This is a **local queue table**, not Kafka.

### 3.5 Distributed tracing

One client request crosses Gateway → Account. You need one **trace ID** in logs of both services.

- Gateway creates/continues a span  
- Sends `traceparent` HTTP header to Account  
- Both write JSON logs including `traceId`  

In Compose, traces also go to **Jaeger** for a UI picture of the path.

---

## 4. Repository layout (mental map)

```text
CharlesSchwabAssignment/
├── SPECS.md                 # Design decisions (read before interview)
├── README.md                # How to run / test
├── LEARNING_GUIDE.md        # This file
├── ProblemStatement.md      # Original assignment
├── pom.xml                  # Parent Maven project (ties 2 modules)
├── docker-compose.yml       # Starts apps + Jaeger + Prometheus + OTel collector
├── otel-collector-config.yaml
├── prometheus.yml
├── pacts/                   # Pact contract file between services
├── event-gateway/           # Microservice 1
└── account-service/         # Microservice 2
```

Each service roughly uses the same package layers:

```text
.../api/       Controllers + DTOs (HTTP in/out)
.../service/   Business logic
.../domain/    Entities + repositories (database)
.../config/    Beans, timeouts, metrics wiring
.../client/    (Gateway only) HTTP client to Account
.../metrics/   (Gateway only) Custom counters
```

---

## 5. Account Service — simpler app first

Start here. It’s the domain core with fewer moving parts.

### 5.1 Startup

| File | Purpose |
|------|---------|
| `AccountServiceApplication.java` | Spring Boot `main()`. Starts the JVM web app on port **8081**. |
| `application.yml` | Port, H2 DB URL, Actuator/Prometheus/tracing settings. |
| `logback-spring.xml` | Forces **JSON logs** with `service`, `traceId`, `spanId`. |

### 5.2 Domain (database)

| File | Purpose |
|------|---------|
| `TransactionType.java` | Enum: `CREDIT` or `DEBIT`. |
| `TransactionEntity.java` | One DB row = one applied transaction. PK = `eventId`. |
| `TransactionRepository.java` | Spring Data JPA: save/find, plus `computeBalance(...)` SQL. |

**Balance query (idea):**  
For one account, add credits, subtract debits. Implemented in `TransactionRepository.computeBalance`.

### 5.3 API (HTTP)

| File | Purpose |
|------|---------|
| `TransactionRequest.java` | JSON body for apply-transaction (validated). |
| `TransactionResponse.java` | JSON returned after apply. |
| `BalanceResponse.java` | `{ accountId, balance, currency }` |
| `AccountResponse.java` | Balance + recent transactions. |
| `AccountController.java` | Maps URLs → service methods. |
| `HealthController.java` | `GET /health` — checks DB with `SELECT 1`. |
| `ErrorResponse.java` / `RestExceptionHandler.java` | Turn validation/errors into consistent JSON + HTTP codes. |
| `PrometheusController.java` | `GET /actuator/prometheus` scrape text. |

**Controller endpoints:**

| HTTP | Path | What it does |
|------|------|--------------|
| POST | `/accounts/{accountId}/transactions` | Apply txn (idempotent) |
| GET | `/accounts/{accountId}/balance` | Balance |
| GET | `/accounts/{accountId}` | Details + recent txns |
| GET | `/health` | Liveness |

### 5.4 Business logic (the important brain)

**File:** `AccountLedgerService.java`

`applyTransaction(...)` flow:

```text
1. Look up eventId in DB
2. If exists → return original (duplicate; HTTP 200)
3. If new → insert row → return created (HTTP 201)
```

Balance is never “`balance = balance + amount` on arrival.”  
It is always recomputed from the **full ledger** of stored transactions.

### 5.5 Config extras

| File | Purpose |
|------|---------|
| `MetricsConfig.java` | Creates Prometheus registry beans so metrics scraping works. |

---

## 6. Event Gateway — front door + resiliency

### 6.1 Startup

| File | Purpose |
|------|---------|
| `EventGatewayApplication.java` | Starts Gateway on **8080**. `@EnableScheduling` turns on the outbox drain timer. |
| `application.yml` | Account base URL, Resilience4j CB/Retry/RateLimiter settings, async-fallback flag. |
| `logback-spring.xml` | Same JSON logging pattern as Account. |

### 6.2 Domain (Gateway’s own event store)

| File | Purpose |
|------|---------|
| `EventType.java` | CREDIT / DEBIT |
| `EventStatus.java` | `PENDING` (queued) or `APPLIED` (Account succeeded) |
| `EventEntity.java` | DB row for each accepted event (including outbox queue) |
| `EventRepository.java` | Find by id, list by account ordered by timestamp, find PENDING for drain |

**Why Gateway stores events at all?**

- Assignment requires Gateway GETs to work even if Account is down  
- Outbox (`PENDING`) needs somewhere local to wait  

### 6.3 HTTP API

| File | Purpose |
|------|---------|
| `EventRequest.java` | Incoming POST JSON + Bean Validation (`@NotBlank`, amount > 0, …) |
| `EventResponse.java` | Outgoing event JSON including `status` |
| `EventController.java` | `POST/GET /events` |
| `AccountProxyController.java` | Optional `GET /accounts/...` proxies to Account; returns 503 if Account down |
| `HealthController.java` | Gateway health |
| `RestExceptionHandler.java` | Maps validation → 400, rate limit → 429, open circuit (proxy) → 503 |
| `ErrorResponse.java` | Error JSON shape |
| `PrometheusController.java` | Metrics scrape |

### 6.4 Calling Account Service

**File:** `AccountServiceClient.java`

This is the **only** class that HTTP-posts to Account.

```text
@Retry(name = "accountService")
@CircuitBreaker(name = "accountService")
public void applyTransaction(EventRequest request) { ... RestClient POST ... }
```

What the annotations mean:

| Annotation | Everyday meaning |
|------------|------------------|
| `@Retry` | If Account blips (timeout/5xx), try again a few times with backoff + jitter |
| `@CircuitBreaker` | If Account keeps failing, stop calling for a cool-down period |
| RestClient timeouts | Don’t hang forever waiting for Account |

Also records latency metric `account_service_call_duration_seconds`.

**Config of the client:** `AppConfig.java` builds `RestClient` with timeouts + observation + trace interceptor.

### 6.5 Core write path (memorize this)

**File:** `EventService.java` → method `submit`

```text
POST /events
    │
    ├─ RateLimiter (too many requests? → 429)
    ├─ Validation already done by Spring on EventRequest
    │
    ├─ eventId already in Gateway DB?
    │     PENDING  → return 202 + same body
    │     APPLIED  → return 200 + same body   (idempotent)
    │
    ├─ Call AccountServiceClient.applyTransaction(...)
    │     success → save event APPLIED → 201
    │     Account 4xx → reject, do not save
    │     Account down / timeout / circuit open:
    │         async-fallback ON  → save PENDING → 202
    │         async-fallback OFF → 503
```

**Why save APPLIED only after Account success?**  
Avoids “Gateway thinks it’s applied but Account never saw it.”

**Why Account is also idempotent?**  
If Gateway crashes after Account applied but before Gateway saved, client retry is safe.

### 6.6 Outbox drain (background)

**File:** `OutboxDrainService.java`

Every ~2 seconds:

```text
1. If circuit breaker is OPEN → skip (don’t hammer Account)
2. Load all PENDING events (oldest first)
3. For each: call Account again
4. Success → mark APPLIED
5. Failure → bump attemptCount, leave PENDING
```

This is the **bonus async fallback**.

### 6.7 Metrics

**File:** `EventMetrics.java`

Custom metrics you’ll mention in interview:

| Metric | Meaning |
|--------|---------|
| `events_submitted_total{result=...}` | created / duplicate / rejected / queued / rate_limited / unavailable |
| `outbox_pending_events` | How many PENDING waiting |
| `outbox_drain_success_total` | Successful background applies |

### 6.8 Tracing helpers

| File | Purpose |
|------|---------|
| `TracePropagationInterceptor.java` | On every outbound HTTP call, set `traceparent` header from current span |
| `GatewayProperties.java` | Typed config: Account URL, timeouts, async-fallback flag, drain interval |

### 6.9 MetricsConfig

Same idea as Account: ensure Prometheus registry beans exist.

---

## 7. End-to-end story (one credit event)

Happy path:

```text
1. Client POSTs event to Gateway :8080/events
2. EventController → EventService.submit
3. Rate limit OK; validation OK; eventId new
4. AccountServiceClient POSTs to Account :8081/.../transactions
5. TracePropagationInterceptor adds traceparent
6. AccountController → AccountLedgerService.applyTransaction
7. Transaction saved in Account H2
8. Gateway persists event as APPLIED in Gateway H2
9. Client gets 201 + event JSON
10. Both services logged the same traceId in JSON logs
```

Account down (fallback on):

```text
1–3 same
4. Call to Account fails (or circuit open)
5. Gateway saves PENDING
6. Client gets 202
7. Later OutboxDrainService succeeds → APPLIED
8. Client can GET /events/{id} anytime (even during PENDING) from Gateway DB
```

Duplicate:

```text
1. Same eventId arrives again
2. Gateway finds existing row → return 200/202 without calling Account again (if already known)
```

---

## 8. Observability stack (Docker Compose)

| Compose service | Purpose |
|-----------------|---------|
| `event-gateway` | Your public API |
| `account-service` | Your ledger |
| `otel-collector` | Receives OTLP traces from both apps |
| `jaeger` | UI to see request waterfalls (`:16686`) |
| `prometheus` | Scrapes `/actuator/prometheus` (`:9090`) |

Config files:

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Wires containers, ports, env vars |
| `otel-collector-config.yaml` | OTLP in → export to Jaeger |
| `prometheus.yml` | Which hosts to scrape |

You do **not** need to memorize YAML details; know the **path**: app → collector → Jaeger, and Prometheus scrapes metrics endpoints.

---

## 9. Tests — what each one proves

### Gateway tests (`event-gateway/src/test/...`)

| Test class | What you’re proving |
|------------|---------------------|
| `EventGatewayIntegrationTest` | Happy path, idempotency, ordering, outbox drain, GETs when Account down, trace header outbound, Prometheus series, health |
| `StrictDegradationTest` | With `async-fallback=false`, Account down → **503** |
| `RateLimitTest` | Burst over limit → **429** |
| `RetryBehaviorTest` | Account returns 500 twice then 201 → still succeeds after retries |
| `AccountServiceConsumerPactTest` | Gateway’s expected Account API shape (consumer side of Pact) |

Gateway tests fake Account with **WireMock** (fake HTTP server), so you don’t need Account running.

### Account tests

| Test class | What you’re proving |
|------------|---------------------|
| `AccountServiceIntegrationTest` | Apply, idempotency, out-of-order balance math, validation, health |
| `AccountServiceProviderPactTest` | Account really satisfies the Pact file in `pacts/` |

Run everything:

```bash
mvn test
```

---

## 10. Build / Docker packaging

| File | Purpose |
|------|---------|
| Root `pom.xml` | Parent: Java 21, Spring Boot version, shared dependency versions, modules list |
| `event-gateway/pom.xml` | Gateway deps (Web, JPA, Resilience4j, WireMock test, Pact consumer) |
| `account-service/pom.xml` | Account deps (Web, JPA, Pact provider) |
| `*/Dockerfile` | Multi-stage: Maven build → JRE image with the jar |

---

## 11. How to study (recommended order)

### Day pass (understand)

1. Read §1–3 of this guide (problem + concepts)  
2. Skim `ProblemStatement.md` requirements checklist  
3. Read `AccountLedgerService` + `AccountController` only  
4. Read `EventService.submit` + `AccountServiceClient` + `OutboxDrainService`  
5. Trace one happy-path request on paper  

### Interview pass (explain)

Be ready to draw and say:

1. **Two services, separate DBs, sync REST**  
2. **Idempotency via `eventId`** on both sides  
3. **Balance = ledger sum, not arrival-order mutate**  
4. **Circuit breaker + retry + timeout** on Gateway→Account  
5. **PENDING outbox when Account down** (and flag for strict 503)  
6. **GET events still works offline from Gateway**  
7. **traceparent + JSON logs + Prometheus + Jaeger**  
8. **No Kafka here because the brief forbids it; outbox → Kafka is future evolution**

### Code “anchor” files (if memory fails)

| Question they ask | Open this file |
|-------------------|----------------|
| How is a POST handled? | `EventService.java` |
| How does Account apply money? | `AccountLedgerService.java` |
| Where is the HTTP call? | `AccountServiceClient.java` |
| Where is the queue? | `OutboxDrainService.java` + `EventStatus.PENDING` |
| How is balance computed? | `TransactionRepository.computeBalance` |
| Where are CB/Retry settings? | `event-gateway/.../application.yml` (`resilience4j:`) |
| Where are APIs defined? | `EventController` / `AccountController` |

---

## 12. Glossary (quick)

| Term | Meaning here |
|------|----------------|
| **DTO** | Request/response JSON objects (`EventRequest`, …) |
| **Entity** | DB table mapped to a Java class (`EventEntity`) |
| **Repository** | Interface that talks to DB |
| **Controller** | Maps HTTP routes to Java methods |
| **Service** | Business rules |
| **H2** | Lightweight in-memory SQL database (resets when process dies) |
| **Actuator** | Spring production endpoints (`/health`, metrics, …) |
| **WireMock** | Fake HTTP server used in tests |
| **Pact** | Contract test: consumer expectations verified by provider |
| **OTLP** | OpenTelemetry protocol for sending traces |
| **Outbox** | Local table of work to retry later |

---

## 13. Mini exercise (optional, seals understanding)

Without looking:

1. Draw Gateway and Account boxes and the REST arrow  
2. Write the 5–6 step happy-path for `POST /events`  
3. Explain what happens if Account returns 503 twice with fallback on  
4. Explain why listing order uses `eventTimestamp`, not insert time  

If you can do that out loud, you understand the project.

---

## Related docs

| Doc | Use it for |
|-----|------------|
| `SPECS.md` | Exact design decisions + status codes |
| `README.md` | Run / test / Compose commands |
| `ProblemStatement.md` | What the graders asked for |
| `jd.md` | Why Java/Spring/Kafka-as-evolution matter for the role |
