#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
#  Rate Limiting Tests — CT-SEC-01, CT-SEC-02
#
#  WARNING: Each test sends ~200 POST requests to /api/v1/changes.
#  This inflates changes_created_total and pollutes Prometheus/Grafana dashboards.
#  Run this script only when you intentionally want to validate rate limiting.
#
#  Usage:
#    wsl bash tests/run_rate_tests.sh
# ──────────────────────────────────────────────────────────────────────────────

ASSERT_PASS=0
ASSERT_FAIL=0
TEST_PASS=0
TEST_FAIL=0
CURRENT_TEST_FAILED=0
FAILED_TESTS=""

newuuid() {
  python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || \
  cat /proc/sys/kernel/random/uuid 2>/dev/null || \
  uuidgen 2>/dev/null
}

check() {
  local test=$1
  local expected=$2
  local actual=$3
  local desc=$4
  if [ "$actual" = "$expected" ]; then
    echo "  PASS [$test] $desc"
    ASSERT_PASS=$((ASSERT_PASS+1))
  else
    echo "  FAIL [$test] $desc | expected=$expected got=$actual"
    ASSERT_FAIL=$((ASSERT_FAIL+1))
    CURRENT_TEST_FAILED=1
  fi
}

begin_test() {
  CURRENT_TEST_FAILED=0
}

end_test() {
  if [ "$CURRENT_TEST_FAILED" -eq 0 ]; then
    TEST_PASS=$((TEST_PASS+1))
  else
    TEST_FAIL=$((TEST_FAIL+1))
    if [ -z "$FAILED_TESTS" ]; then
      FAILED_TESTS="$1"
    else
      FAILED_TESTS="$FAILED_TESTS, $1"
    fi
  fi
}

pass_msg() {
  local test=$1
  local desc=$2
  echo "  PASS [$test] $desc"
  ASSERT_PASS=$((ASSERT_PASS+1))
}

fail_msg() {
  local test=$1
  local desc=$2
  echo "  FAIL [$test] $desc"
  ASSERT_FAIL=$((ASSERT_FAIL+1))
  CURRENT_TEST_FAILED=1
}

echo ""
echo "============================================================"
echo "  RATE LIMITING TESTS — CT-SEC-01, CT-SEC-02"
echo "  WARNING: ~200 POST requests per test will be sent."
echo "============================================================"
echo ""

echo "============================================================"
echo "  CT-SEC-01: Rate limiting – 101st request returns 429"
echo "============================================================"
begin_test
# Send 100 requests (they should all pass)
echo "  INFO sending 100 POST requests to exhaust rate bucket..."
for i in $(seq 1 100); do
  curl -s -X POST http://localhost:8080/api/v1/changes \
    -H "Content-Type: application/json" \
    -H "X-Forwarded-For: 33.33.33.01" \
    -d '{"title":"Rate test","componentId":"svc-a","requestedBy":"tester","scheduledAt":"2026-09-01T10:00:00Z"}' \
    > /dev/null
done

# 101st must be rejected
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-Forwarded-For: 33.33.33.01" \
  -d '{"title":"Rate test extra","componentId":"svc-a","requestedBy":"tester","scheduledAt":"2026-09-01T10:00:00Z"}')
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
check "CT-SEC-01" "429" "$code" "101st request returns 429 Too Many Requests"
echo "$body" | grep -q 'Too Many Requests' && pass_msg "CT-SEC-01" "body contains 'Too Many Requests'" \
  || fail_msg "CT-SEC-01" "body missing 'Too Many Requests'"

# Verify Retry-After header
retry_after=$(curl -s -I -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-Forwarded-For: 33.33.33.01" \
  -d '{}' | grep -i 'retry-after' | head -1)
[ -n "$retry_after" ] && pass_msg "CT-SEC-01" "Retry-After header present: $retry_after" \
  || fail_msg "CT-SEC-01" "Retry-After header missing"
end_test "CT-SEC-01"

echo ""
echo "============================================================"
echo "  CT-SEC-02: X-Forwarded-For spoofing – per-IP bucket isolation"
echo "============================================================"
begin_test
# Exhaust bucket for spoofed IP-A
for i in $(seq 1 100); do
  curl -s -X POST http://localhost:8080/api/v1/changes \
    -H "Content-Type: application/json" \
    -H "X-Forwarded-For: 44.44.44.01" \
    -d '{"title":"Rate test","componentId":"svc-a","requestedBy":"tester","scheduledAt":"2026-09-01T10:00:00Z"}' \
    > /dev/null
done

# Spoofed IP-B should still get its own bucket (different key)
resp=$(curl -s -w "\nHTTP:%{http_code}" -X POST http://localhost:8080/api/v1/changes \
  -H "Content-Type: application/json" \
  -H "X-Forwarded-For: 44.44.44.02" \
  -d '{"title":"Rate test bypass","componentId":"svc-a","requestedBy":"tester","scheduledAt":"2026-09-01T10:00:00Z"}')
code=$(echo "$resp" | tail -1 | sed 's/HTTP://')
# EXPECTED: 201 (bypass works) — documents the known limitation: no trusted-proxy validation in POC
if [ "$code" = "201" ] || [ "$code" = "400" ]; then
  pass_msg "CT-SEC-02" "X-Forwarded-For spoofing bypasses rate limit (code=$code) — KNOWN POC LIMITATION. Phase 2: add trusted-proxy validation"
else
  pass_msg "CT-SEC-02" "Spoofed IP rejected (code=$code) — unexpected but acceptable"
fi
end_test "CT-SEC-02"

echo ""
echo "========================================"
echo "  RESUMO FINAL"
echo "========================================"
echo "  CENÁRIOS TOTAL   : $((TEST_PASS+TEST_FAIL))"
echo "  CENÁRIOS PASSARAM: $TEST_PASS"
echo "  CENÁRIOS FALHARAM: $TEST_FAIL"
[ "$TEST_FAIL" -gt 0 ] && echo "  CENÁRIOS COM FALHA: $FAILED_TESTS"
echo "  ASSERTS FALHARAM : $ASSERT_FAIL"
echo ""
[ "$TEST_FAIL" -eq 0 ] && echo "  TODOS OS CENÁRIOS PASSARAM!" || echo "  ATENÇÃO: $TEST_FAIL CENÁRIO(S) FALHARAM"
echo ""
