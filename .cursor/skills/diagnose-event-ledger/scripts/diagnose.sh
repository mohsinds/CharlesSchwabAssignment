#!/usr/bin/env bash
# Event Ledger project diagnosis (read-only).
set -u

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

pass=0
warn=0
fail=0

check() {
  local name="$1"
  local status="$2"
  local note="${3:-}"
  printf '%-28s %-6s %s\n' "$name" "$status" "$note"
  case "$status" in
    PASS) pass=$((pass + 1)) ;;
    WARN) warn=$((warn + 1)) ;;
    FAIL) fail=$((fail + 1)) ;;
  esac
}

echo "=== Event Ledger diagnose ==="
echo "root: $ROOT"
echo

# Build
if mvn -q -DskipTests package >/tmp/event-ledger-diagnose-build.log 2>&1; then
  check "Maven package" "PASS"
else
  check "Maven package" "FAIL" "see /tmp/event-ledger-diagnose-build.log"
fi

# Tests
if mvn -q test >/tmp/event-ledger-diagnose-test.log 2>&1; then
  check "Maven test" "PASS"
else
  check "Maven test" "FAIL" "see /tmp/event-ledger-diagnose-test.log"
fi

http_up() {
  local url="$1"
  curl -sf --max-time 2 "$url" >/dev/null 2>&1
}

if http_up "http://localhost:8080/health"; then
  check "Gateway :8080/health" "PASS"
else
  check "Gateway :8080/health" "WARN" "not running (ok if apps not started)"
fi

if http_up "http://localhost:8081/health"; then
  check "Account :8081/health" "PASS"
else
  check "Account :8081/health" "WARN" "not running (ok if apps not started)"
fi

if http_up "http://localhost:3000/api/health"; then
  check "Grafana :3000" "PASS"
else
  check "Grafana :3000" "WARN" "obs stack not up"
fi

if http_up "http://localhost:9090/-/ready"; then
  check "Prometheus :9090" "PASS"
else
  check "Prometheus :9090" "WARN" "obs stack not up"
fi

if curl -sf --max-time 2 "http://localhost:16686/" >/dev/null 2>&1; then
  check "Jaeger :16686" "PASS"
else
  check "Jaeger :16686" "WARN" "obs stack not up"
fi

if http_up "http://localhost:3100/ready"; then
  check "Loki :3100" "PASS"
else
  check "Loki :3100" "WARN" "obs stack not up"
fi

# Docs drift — required keywords
docs_ok=1
for needle in "allowed-negative-balance" "server.shutdown" "GATEWAY_ACCOUNT_RETRY_MAX_ATTEMPTS" "REJECTED"; do
  if ! grep -Rsq --exclude-dir=target --exclude-dir=.git "$needle" README.md SPECS.md PROJECT.md 2>/dev/null; then
    check "Docs mention $needle" "WARN" "missing from README/SPECS/PROJECT"
    docs_ok=0
  fi
done
if [[ "$docs_ok" -eq 1 ]]; then
  check "Docs key flags" "PASS"
fi

echo
echo "summary: PASS=$pass WARN=$warn FAIL=$fail"
if [[ "$fail" -gt 0 ]]; then
  exit 1
fi
exit 0
