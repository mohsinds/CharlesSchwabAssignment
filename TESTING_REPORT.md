# Event Ledger — Testing Report

Quality bar for this repository: **JUnit 5 + Allure**, **JaCoCo ≥ 80% line coverage**, **SonarCloud quality gate**, and GitHub Actions job **`CI Pipeline / test-and-quality`**.

---

## 1. Scenario matrix

| Scenario | Module | Test class / method | Expect |
|----------|--------|---------------------|--------|
| Happy path + idempotency + OOO list | Gateway | `EventGatewayIntegrationTest.submitsEventIdempotentlyAndListsInTimestampOrder` | 201 / 200, ordered list |
| Validation | Gateway / Account | `rejectsInvalidPayloads` / `rejectsInvalidAmountAndUnknownType` | 400 |
| NSF → 422 forward | Gateway | `forwardsInsufficientFundsAs422WithAccountMessage` | 422 + message |
| Outbox PENDING → APPLIED | Gateway | `queuesWhenAccountUnavailableAndDrainsWhenRecovered` | 202 → APPLIED |
| Outbox PENDING → REJECTED | Gateway | `marksOutboxRejectedOnPermanentAccount4xx` | REJECTED |
| GETs when Account down | Gateway | `getsStillWorkWhenAccountIsDown` | 200 local / 503 proxy |
| Trace propagation | Gateway | `propagatesTraceParentHeaderToAccountService` | `traceparent` outbound |
| Rate limit | Gateway | `RateLimitTest.returns429WhenRateLimitExceeded` | 429 |
| Strict degradation | Gateway | `StrictDegradationTest` | 503 when fallback off |
| Retry then success | Gateway | `RetryBehaviorTest.retriesTransientFailuresThenSucceeds` | 3 calls, 201 |
| Retry exhaust → queue | Gateway | `RetryBehaviorTest.exhaustsRetriesThenQueuesPending` | 3×500 → 202 PENDING |
| No retry on 4xx | Gateway | `RetryBehaviorTest.doesNotRetryClientErrors` | 1 call, 422 |
| Configurable maxAttempts | Gateway | `ConfigurableRetryAttemptsTest` | maxAttempts=2 → 2 calls |
| Circuit OPEN + fallback | Gateway | `CircuitBreakerOpenTest` | OPEN + 202 PENDING |
| Graceful shutdown (outbox) | Gateway | `GracefulShutdownTest` | drain skipped, 0 Account calls |
| Ledger OOO + conflict 409 | Account | `AccountServiceIntegrationTest` | balance math / 409 |
| NSF guard | Account | `NegativeBalanceGuardTest` | 422 / funded debit / CREDIT ok |
| Graceful shutdown flag | Account | `GracefulShutdownGuardTest` | `isShuttingDown()` |
| Pact consumer / provider | Both | `*PactTest` | contract verify |
| Health / Prometheus | Both | integration tests | UP + custom series |

---

## 2. Local commands

```bash
# Unit/integration tests + JaCoCo report + 80% gate
mvn -B verify

# Allure HTML (requires Allure CLI or plugin)
mvn -pl event-gateway allure:report
mvn -pl event-gateway allure:serve

# SonarCloud (needs SONAR_TOKEN)
mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.token="$SONAR_TOKEN" \
  -Dsonar.qualitygate.wait=true
```

Reports:

| Artifact | Path |
|----------|------|
| JaCoCo HTML | `*/target/site/jacoco/index.html` |
| JaCoCo XML | `*/target/site/jacoco/jacoco.xml` |
| Allure results | `*/target/allure-results` |

---

## 3. CI Pipeline (`test-and-quality`)

Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

| Trigger | Branches |
|---------|----------|
| `push` | `main` |
| `pull_request` | `main` |

Steps: checkout → Java 21 → `mvn verify` → SonarCloud (quality gate wait) → aggregate Allure → deploy Allure to **GitHub Pages** on success.

**Required check for branch protection:** `CI Pipeline / test-and-quality`

Allure site (after first green deploy): `https://mohsinds.github.io/CharlesSchwabAssignment/`

---

## 4. SonarCloud quality gate (configure in UI)

Project key: `mohsinds_CharlesSchwabAssignment`  
Org: `mohsinds`  
Host: `https://sonarcloud.io`

| Metric | Condition | Fail if |
|--------|-----------|---------|
| Coverage | less than | **80%** |
| Code Smells | greater than | **10** |
| Vulnerabilities / Security Hotspots (new) | greater than | **0** |

Repo secret: `SONAR_TOKEN` (SonarCloud user/project token).

```bash
gh secret set SONAR_TOKEN --body "<token>" --repo mohsinds/CharlesSchwabAssignment
```

---

## 5. Branch protection (`main`)

After the check has run at least once:

- Require status checks: **CI Pipeline / test-and-quality**
- Require pull request reviews (1+)
- Dismiss stale reviews on new commits

See ops steps in README / this report §6.

---

## 6. Ops checklist

| Step | Status |
|------|--------|
| CI workflow + tests | Done (PR [#1](https://github.com/mohsinds/CharlesSchwabAssignment/pull/1)) |
| Branch protection on `main` | Done — require `CI Pipeline / test-and-quality`, 1 review, dismiss stale |
| GitHub Pages (Actions) | Enabled — https://mohsinds.github.io/CharlesSchwabAssignment/ |
| `SONAR_TOKEN` secret | **You must set** — `gh secret set SONAR_TOKEN --repo mohsinds/CharlesSchwabAssignment` |
| SonarCloud project + gates | **You must configure in UI** — coverage &lt; 80%, smells &gt; 10, security &gt; 0 |

Until `SONAR_TOKEN` is set, the Sonar step in CI will fail and the PR stays blocked (by design).
