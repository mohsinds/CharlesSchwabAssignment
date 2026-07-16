---
description: Diagnose Event Ledger project health — build, tests, services, observability, docs drift
---

# Diagnose Event Ledger

Run a read-only health check of this repository and report findings. Prefer the project skill [diagnose-event-ledger](.cursor/skills/diagnose-event-ledger/SKILL.md) and script `.cursor/skills/diagnose-event-ledger/scripts/diagnose.sh`.

## Steps

1. Read [`PROJECT.md`](PROJECT.md) for expected architecture and scope.
2. Run `.cursor/skills/diagnose-event-ledger/scripts/diagnose.sh` (from repo root).
3. If the script cannot run, manually:
   - `mvn -q -DskipTests package`
   - `mvn -q test`
   - `curl -sf http://localhost:8080/health` and `:8081/health` (note if down)
   - `curl -sf http://localhost:3000/api/health`, `:9090/-/ready`, `:16686/`, `:3100/ready` (note if down)
   - Confirm `README.md`, `SPECS.md`, and `PROJECT.md` mention current flags: `allowed-negative-balance`, graceful shutdown, `GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS`
4. Summarize: **PASS / WARN / FAIL** per check, then a short overall verdict and next actions.

Do not commit, push, or change code unless the user asks to fix findings.
