# End-to-End Testing Guide

Walkthrough for exercising the Event Ledger with **Swagger UI** and inspecting each service’s **H2 database**.

---

## 1. Start the stack

### Option A — Docker Compose (recommended)

```bash
docker compose up --build
```

### Option B — Local Maven apps + observability (Grafana/Loki/Jaeger)

```bash
PROMETHEUS_CONFIG=./prometheus.local.yml docker compose up jaeger otel-collector prometheus loki grafana

# Terminal 1
mvn -pl account-service spring-boot:run

# Terminal 2
mvn -pl event-gateway spring-boot:run
```

### Option C — Local Maven apps only

```bash
# Terminal 1
mvn -pl account-service spring-boot:run

# Terminal 2
mvn -pl event-gateway spring-boot:run
```

Wait until both health checks succeed:

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

---

## 2. Bookmark these URLs

| What | URL |
|------|-----|
| **Gateway Swagger UI** | http://localhost:8080/swagger-ui.html |
| **Account Swagger UI** | http://localhost:8081/swagger-ui.html |
| Gateway OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Account OpenAPI JSON | http://localhost:8081/v3/api-docs |
| **Gateway H2 console** | http://localhost:8080/h2-console |
| **Account H2 console** | http://localhost:8081/h2-console |
| Jaeger (Compose) | http://localhost:16686 |
| Prometheus (Compose) | http://localhost:9090 |
| **Grafana** | http://localhost:3000 (admin / admin) |
| Loki | http://localhost:3100 |

Use **Gateway Swagger** for the public client path. Use **Account Swagger** only when you want to hit the ledger API directly.

---

## 3. Connect to each H2 database

Open each console in a browser tab. Sign in with:

### Event Gateway DB

| Field | Value |
|-------|--------|
| Driver Class | `org.h2.Driver` |
| JDBC URL | `jdbc:h2:mem:gatewaydb` |
| User Name | `sa` |
| Password | *(leave empty)* |

### Account Service DB

| Field | Value |
|-------|--------|
| Driver Class | `org.h2.Driver` |
| JDBC URL | `jdbc:h2:mem:accountdb` |
| User Name | `sa` |
| Password | *(leave empty)* |

**Important:** H2 is **in-memory** and lives inside that JVM process. You must connect to the same service URL (`:8080` or `:8081`). A wrong JDBC URL creates an empty private DB, so queries look “empty.”

Useful SQL after you submit traffic:

```sql
-- Gateway: accepted / queued / applied events
SELECT EVENT_ID, ACCOUNT_ID, TYPE, AMOUNT, STATUS, EVENT_TIMESTAMP, CREATED_AT, ATTEMPT_COUNT
FROM EVENTS
ORDER BY CREATED_AT;

-- Account: money ledger
SELECT EVENT_ID, ACCOUNT_ID, TYPE, AMOUNT, CURRENCY, EVENT_TIMESTAMP, APPLIED_AT
FROM TRANSACTIONS
ORDER BY EVENT_TIMESTAMP, EVENT_ID;
```

---

## 4. Happy-path E2E (Swagger + DB)

### Step A — Submit a credit via Gateway Swagger

1. Open http://localhost:8080/swagger-ui.html
2. Expand **Events** → `POST /events` → **Try it out**
3. Use this body (Swagger should already suggest similar examples):

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

4. **Execute** — expect **201 Created** and `status: "APPLIED"`.

### Step B — Confirm Gateway DB

In Gateway H2 console:

```sql
SELECT * FROM EVENTS WHERE EVENT_ID = 'evt-001';
```

Expect one row with `STATUS = 'APPLIED'`.

### Step C — Confirm Account DB

In Account H2 console:

```sql
SELECT * FROM TRANSACTIONS WHERE EVENT_ID = 'evt-001';
```

Expect one row for `acct-123` / CREDIT / `150.00`.

### Step D — Check balance through Gateway

In Gateway Swagger: **Accounts (proxy)** → `GET /accounts/{accountId}/balance`

- `accountId` = `acct-123`
- Expect balance **150.00**

Or Account Swagger: `GET /accounts/{accountId}/balance` with the same id.

### Step E — Submit a debit + re-check both DBs

`POST /events` again:

```json
{
  "eventId": "evt-002",
  "accountId": "acct-123",
  "type": "DEBIT",
  "amount": 40.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T15:00:00Z"
}
```

Expected balance: **110.00** (`150 − 40`).

Verify:

```sql
-- Gateway
SELECT EVENT_ID, TYPE, AMOUNT, STATUS FROM EVENTS WHERE ACCOUNT_ID = 'acct-123';

-- Account
SELECT EVENT_ID, TYPE, AMOUNT FROM TRANSACTIONS WHERE ACCOUNT_ID = 'acct-123';
```

Both DBs should show `evt-001` and `evt-002`. They are **not replicated**; Gateway wrote after Account accepted the apply over REST.

---

## 5. Idempotency check

Re-submit **exactly** the same `evt-001` body via Gateway Swagger.

- Expect **200 OK** (duplicate already applied), not another credit.
- Re-run the SQL counts — still **one** row per table for `evt-001`.

---

## 6. Out-of-order listing

Submit an older timestamp *after* a newer one, e.g. `evt-003` with `eventTimestamp` = `2026-05-15T13:00:00Z`.

Then Gateway Swagger: `GET /events?account=acct-123`

Listing order follows **business time** (`eventTimestamp`), not arrival time.

Balance still ignores order: it is always `SUM(CREDIT) − SUM(DEBIT)`.

---

## 7. Async fallback (Account down) — optional

Shows why Gateway has its **own** DB / outbox.

1. Stop Account only:

```bash
# Compose
docker compose stop account-service

# Or stop the Maven Account process if running locally
```

2. Gateway Swagger → `POST /events` with a new id, e.g. `evt-queue-1`.
3. Expect **202 Accepted** and `status: "PENDING"`.
4. Gateway H2:

```sql
SELECT EVENT_ID, STATUS, ATTEMPT_COUNT FROM EVENTS WHERE EVENT_ID = 'evt-queue-1';
```

5. Account H2 (if still reachable while stopped, skip): no matching transaction yet.
6. Restart Account:

```bash
docker compose start account-service
```

7. Wait a few seconds (outbox drain ~2s). Refresh Gateway SQL — `STATUS` should become `APPLIED`. Account `TRANSACTIONS` should gain `evt-queue-1`.

---

## 8. Suggested full script (Swagger clicks + SQL)

| # | Action | Where | Expect |
|---|--------|-------|--------|
| 1 | Health | both Swagger `/health` | `status: UP` |
| 2 | POST credit `evt-001` | Gateway Swagger | 201 APPLIED |
| 3 | Query Gateway `EVENTS` | H2 `:8080` | 1 APPLIED row |
| 4 | Query Account `TRANSACTIONS` | H2 `:8081` | 1 ledger row |
| 5 | GET balance | Gateway proxy | 150.00 |
| 6 | POST debit `evt-002` | Gateway Swagger | 201 APPLIED |
| 7 | GET balance | Gateway proxy | 110.00 |
| 8 | Re-POST `evt-001` | Gateway Swagger | 200, no double-credit |
| 9 | GET `/events?account=` | Gateway Swagger | ordered by timestamp |
| 10 | (Optional) queue while Account down | Gateway + H2 | PENDING → APPLIED after restart |

---

## 9. Observability checks (problem statement §4)

### Structured JSON logging

Both services log JSON lines with `timestamp`, `level`, `service`, `env`, `namespace`, `message`, and (on request paths) `traceId` / `spanId`. The same lines are pushed to **Loki** (labels: `service`, `env`, `namespace=event-ledger`) for Grafana.

```bash
# Compose
docker compose logs -f event-gateway account-service

# Or watch the IntelliJ Run console while POSTing an event

# Grafana Explore → Loki
# {namespace="event-ledger", service="event-gateway"}
```

Example shape after `POST /events`:

```json
{
  "timestamp": "2026-05-15T14:02:11.123Z",
  "level": "INFO",
  "service": "event-gateway",
  "env": "local",
  "namespace": "event-ledger",
  "message": "Received event submission eventId=evt-001 accountId=acct-123",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7"
}
```

Account Service logs for the same request should share the same `traceId` (propagated via `traceparent`).

### Grafana dashboards

| Mode | How |
|------|-----|
| Full Compose | `docker compose up --build` → http://localhost:3000 |
| Local Maven/IDE apps | `PROMETHEUS_CONFIG=./prometheus.local.yml docker compose up jaeger otel-collector prometheus loki grafana` then `mvn -pl … spring-boot:run` |

Login: `admin` / `admin` (anonymous Viewer also enabled). Open **Event Ledger Overview** and **Jaeger Monitor**.

### Tags (metrics, logs, traces)

| Tag | Metrics | Loki labels | Jaeger / OTel |
|-----|---------|-------------|----------------|
| `service` / `application` | Micrometer common tags | Loki label | `service.name` |
| `env` | Micrometer common tags | Loki label | `deployment.environment` |
| `namespace` | Micrometer common tags | Loki label | `service.namespace=event-ledger` |

### Health

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

Expect `status` and `database` = `UP`.

### Custom metrics

```bash
# Gateway — event outcomes + latency histogram + outbox gauges
curl -s http://localhost:8080/actuator/prometheus | grep -E 'events_submitted_total|account_service_call_duration|outbox_'

# Account — transactions applied
curl -s http://localhost:8081/actuator/prometheus | grep transactions_applied_total
```

With Compose, open Prometheus at http://localhost:9090 and query e.g. `events_submitted_total{service="event-gateway"}`.
Or use Grafana → Event Ledger Overview.

---

## 10. Troubleshooting

| Symptom | Likely cause |
|---------|----------------|
| H2 console shows no tables / empty results | Wrong JDBC URL — must be `gatewaydb` on `:8080` and `accountdb` on `:8081` |
| Gateway 503 on `POST /events` | Account down **and** `GATEWAY_ASYNC_FALLBACK_ENABLED=false` |
| Gateway 202 but Account never gets row | Account still down, or drain failing — check Gateway logs / `ATTEMPT_COUNT` |
| Swagger UI 404 | Service not up yet; try `/swagger-ui/index.html` |
| Balance 404 | No transactions yet for that `accountId` |
| Logs missing `traceId` | Log line outside a request span (startup/shutdown); POST an event and re-check request logs |
| Metric series missing | Hit the endpoint once, then scrape `/actuator/prometheus` again |

---

## 11. Architecture reminder while testing

```text
POST /events  →  Gateway H2 (events)  →  REST  →  Account H2 (transactions)
                      ↑                              ↑
                 inspect here                   inspect here
              :8080/h2-console               :8081/h2-console
```

There is no DB replication. Matching `eventId` rows appear because the Gateway called Account successfully (or the outbox drain did later).
