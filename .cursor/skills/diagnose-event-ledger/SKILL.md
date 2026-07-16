---
name: diagnose-event-ledger
description: Diagnose Event Ledger project health — Maven build/tests, running services, observability endpoints, and docs drift against PROJECT.md. Use when the user asks to diagnose, doctor, health-check, or verify the project.
---

# Diagnose Event Ledger

## Instructions

1. From the repo root, run:

```bash
bash .cursor/skills/diagnose-event-ledger/scripts/diagnose.sh
```

2. If services are expected to be up but curls fail, note which ports are down (do not start Docker unless asked).

3. Produce a concise report:

```markdown
## Event Ledger diagnosis
| Check | Result | Notes |
|-------|--------|-------|
| Build | PASS/FAIL | ... |
| Tests | PASS/FAIL | ... |
| Gateway :8080 | UP/DOWN | ... |
| Account :8081 | UP/DOWN | ... |
| Grafana/Prom/Jaeger/Loki | ... | ... |
| Docs flags | PASS/WARN | ... |

### Verdict
One sentence.

### Next actions
- Bullet list (only if needed)
```

4. Cross-check live behavior against [`PROJECT.md`](../../../PROJECT.md) scope and lifecycle.

## Additional resources

- Script details: [scripts/diagnose.sh](scripts/diagnose.sh)
- Project knowledge: [PROJECT.md](../../../PROJECT.md)
