#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
#  run_integration_tests.sh — Run all integration tests (backend only)
#
#  Backend (Maven): files matching **/*IT (Testcontainers — needs Docker running)
#
#  Both services are covered:
#    change-service      — CreateChangeIT
#    deploy-orchestrator — DeployEventConsumerIT
#
#  IMPORTANT: Docker must be running. Testcontainers will spin up PostgreSQL
#  and Kafka containers automatically; no stack startup is required.
#
#  Usage:
#    wsl bash tests/run_integration_tests.sh
# ──────────────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}" .sh)"
TIMESTAMP="$(date '+%Y%m%d%H%M%S')_$$"
LOG_DIR="$SCRIPT_DIR/logs/$SCRIPT_NAME"
LOG_FILE="$LOG_DIR/$TIMESTAMP.txt"
mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1
echo "  LOG $LOG_FILE"

ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Global counters ───────────────────────────────────────────────────────────
OVERALL_EXIT=0

BACKEND_TOTAL_RUN=0
BACKEND_TOTAL_FAILURES=0
BACKEND_TOTAL_ERRORS=0
BACKEND_TOTAL_SKIPPED=0
BACKEND_SERVICES_PASSED=0
BACKEND_SERVICES_FAILED=0
BACKEND_FAILED_SERVICES=""
BACKEND_FAILED_TESTS=""

# ── Helpers ───────────────────────────────────────────────────────────────────

# Extract a single integer following "label: " from a Maven results line.
# Usage: mvn_field "Failures" "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"
mvn_field() {
  local label="$1"
  local line="$2"
  echo "$line" | grep -o "${label}: [0-9]*" | grep -o "[0-9]*"
}

# Run integration tests for one backend service and accumulate counters.
# Usage: run_backend_it "change-service" "$ROOT_DIR/backend/change-service"
run_backend_it() {
  local svc_name="$1"
  local svc_dir="$2"
  local tmp_out
  tmp_out="$(mktemp)"

  echo ""
  echo "========================================"
  echo "  INTEGRATION TESTS: $svc_name"
  echo "========================================"

  (cd "$svc_dir" && mvn -B test -Dtest="**/*IT" -DfailIfNoTests=false 2>&1) \
    | tee "$tmp_out"
  local exit_code="${PIPESTATUS[0]}"

  # Find the final Maven results summary line (no trailing "-- in ClassName")
  local summary_line
  summary_line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" \
    "$tmp_out" | tail -1)"

  if [ -n "$summary_line" ]; then
    local run failures errors skipped
    run="$(mvn_field "Tests run" "$summary_line")"
    failures="$(mvn_field "Failures" "$summary_line")"
    errors="$(mvn_field "Errors" "$summary_line")"
    skipped="$(mvn_field "Skipped" "$summary_line")"

    run="${run:-0}"; failures="${failures:-0}"; errors="${errors:-0}"; skipped="${skipped:-0}"

    BACKEND_TOTAL_RUN=$((BACKEND_TOTAL_RUN + run))
    BACKEND_TOTAL_FAILURES=$((BACKEND_TOTAL_FAILURES + failures))
    BACKEND_TOTAL_ERRORS=$((BACKEND_TOTAL_ERRORS + errors))
    BACKEND_TOTAL_SKIPPED=$((BACKEND_TOTAL_SKIPPED + skipped))

    echo ""
    echo "  --- $svc_name: run=$run  failures=$failures  errors=$errors  skipped=$skipped ---"
  else
    echo ""
    echo "  WARN: no integration tests found or could not parse Maven test summary for $svc_name"
  fi

  if [ "$exit_code" -eq 0 ]; then
    BACKEND_SERVICES_PASSED=$((BACKEND_SERVICES_PASSED + 1))
    echo "  RESULT [$svc_name]: PASSED"
  else
    BACKEND_SERVICES_FAILED=$((BACKEND_SERVICES_FAILED + 1))
    OVERALL_EXIT=1
    if [ -z "$BACKEND_FAILED_SERVICES" ]; then
      BACKEND_FAILED_SERVICES="$svc_name"
    else
      BACKEND_FAILED_SERVICES="$BACKEND_FAILED_SERVICES, $svc_name"
    fi
    echo "  RESULT [$svc_name]: FAILED (exit=$exit_code)"
  fi

  # Collect individual failed test names from Maven [ERROR] lines
  local raw_failures
  raw_failures="$(grep -E "\[ERROR\] .*<<< (FAILURE|ERROR)!" "$tmp_out" \
    | grep -v "Tests run:" \
    | sed 's/^\[ERROR\] //; s/ -- Time elapsed.*//')"
  if [ -n "$raw_failures" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      BACKEND_FAILED_TESTS="${BACKEND_FAILED_TESTS}    [$svc_name] ${line}"$'\n'
    done <<< "$raw_failures"
  fi

  rm -f "$tmp_out"
}

# ── Pre-flight: verify Docker is available ────────────────────────────────────

echo ""
echo "============================================================"
echo "  CHANGEOPS — INTEGRATION TEST SUITE"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"
echo ""
echo "  NOTE: Testcontainers will spin up PostgreSQL + Kafka automatically."
echo "        Docker must be running. No manual stack startup needed."
echo ""

if ! docker info > /dev/null 2>&1; then
  echo "  ERROR: Docker is not running or not accessible."
  echo "         Start Docker and retry."
  exit 1
fi
echo "  INFO: Docker OK"

# ── Run all integration test suites ──────────────────────────────────────────

run_backend_it "change-service" "$ROOT_DIR/backend/change-service"
run_backend_it "deploy-orchestrator" "$ROOT_DIR/backend/deploy-orchestrator"

# ── Final compiled summary ────────────────────────────────────────────────────

BACKEND_TOTAL_PASSED=$((BACKEND_TOTAL_RUN - BACKEND_TOTAL_FAILURES - BACKEND_TOTAL_ERRORS))
BACKEND_TOTAL_ISSUES=$((BACKEND_TOTAL_FAILURES + BACKEND_TOTAL_ERRORS))
TOTAL_SERVICES=$((BACKEND_SERVICES_PASSED + BACKEND_SERVICES_FAILED))

echo ""
echo "============================================================"
echo "  COMPILED SUMMARY — INTEGRATION TESTS"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"
echo ""
echo "  ── BACKEND ──────────────────────────────────────────────"
echo "  Services passed : $BACKEND_SERVICES_PASSED / $TOTAL_SERVICES"
[ "$BACKEND_SERVICES_FAILED" -gt 0 ] && \
  echo "  Services failed : $BACKEND_SERVICES_FAILED  [$BACKEND_FAILED_SERVICES]"
echo "  Tests run       : $BACKEND_TOTAL_RUN"
echo "  Tests passed    : $BACKEND_TOTAL_PASSED"
echo "  Tests failed    : $BACKEND_TOTAL_FAILURES"
echo "  Tests errors    : $BACKEND_TOTAL_ERRORS"
echo "  Tests skipped   : $BACKEND_TOTAL_SKIPPED"
if [ -n "$BACKEND_FAILED_TESTS" ]; then
  echo ""
  echo "  Failed tests:"
  printf '%s' "$BACKEND_FAILED_TESTS"
fi
echo ""
echo "  ── OVERALL ──────────────────────────────────────────────"
echo "  Total tests run : $BACKEND_TOTAL_RUN"
echo "  Total passed    : $BACKEND_TOTAL_PASSED"
echo "  Total failed    : $BACKEND_TOTAL_ISSUES"
echo "  Total skipped   : $BACKEND_TOTAL_SKIPPED"

if [ "$OVERALL_EXIT" -eq 0 ]; then
  echo ""
  echo "  STATUS: ALL INTEGRATION TESTS PASSED"
else
  echo ""
  echo "  STATUS: INTEGRATION TESTS FAILED"
fi
echo "============================================================"

exit "$OVERALL_EXIT"
