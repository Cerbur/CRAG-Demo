#!/bin/bash
# CRAG-Demo Query Stub Failure HTTP Regression
# 验证 Stub Failure 模式下 Query API 返回 502/50201，并自动恢复 Success 模式。
#
# 用法: bash scripts/tests/http/query_stub_failure_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8080
#
# 注意事项:
#   - 本脚本会重建 app 容器，请确保已在目标 Compose 目录中（docker compose 可用）。
#   - 脚本结束时自动将应用恢复为 CRAG_QUERY_LLM_STUB_MODE=success。

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
RUN_ID="qf-$(date +%s)-$$"
FAILED=0
COMPOSE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"

echo "=== Query Stub Failure HTTP Regression ==="
echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"
echo "COMPOSE_DIR=$COMPOSE_DIR"

# ── Helper: POST request, asserts HTTP status in range ──
# Sets globals RESP_BODY, RESP_CODE.
http_post_raw() {
  local url="$1"
  local data="$2"
  local desc="$3"
  local raw
  raw=$(curl -s -w '\n%{http_code}' -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$data" || printf '{"code":-1}\n000')
  RESP_CODE=$(printf '%s' "$raw" | tail -1)
  RESP_BODY=$(printf '%s' "$raw" | sed '$d')
  echo "$desc — HTTP $RESP_CODE"
}

# ── Helper: extract JSON code field from body ──
json_code() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1"
}

# ── Helper: wait for app to be ready ──
# Polls /api/v1/query until any response (not connection refused).
wait_for_app() {
  local label="$1"
  local max_wait=120
  local waited=0
  echo "  Waiting for app to be ready ($label)..."
  while [ $waited -lt $max_wait ]; do
    sleep 3
    waited=$((waited + 3))
    # Use curl with short timeout to detect connection refused
    local resp
    resp=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$BASE_URL/api/v1/query" 2>/dev/null || echo "000")
    if [ "$resp" != "000" ]; then
      echo "  App ready after ${waited}s (HTTP $resp)"
      return 0
    fi
    echo "  Waiting... ${waited}s / ${max_wait}s"
  done
  echo "FAIL: App did not become ready within ${max_wait}s"
  return 1
}

# ════════════════════════════════════════════════════════════
# Test: Failure mode
# ════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 1: Failure mode test ==="

# 1. Rebuild app with CRAG_QUERY_LLM_STUB_MODE=failure
echo "--- 1. Rebuilding app in failure mode ---"
cd "$COMPOSE_DIR"
CRAG_QUERY_LLM_STUB_MODE=failure docker compose up -d --build app
echo "App rebuild initiated (failure mode)"

# 2. Wait for app to be ready
echo "--- 2. Wait for app readiness ---"
if ! wait_for_app "failure mode"; then
  FAILED=1
  echo "FAIL: App did not become ready after failure mode rebuild"
fi

# 3. Send query and assert 502 / 50201
echo ""
echo "--- 3. Send query in failure mode ---"
http_post_raw "$BASE_URL/api/v1/query" '{"question":"测试问题"}' "Query in failure mode"

if [ "$RESP_CODE" = "502" ]; then
  echo "PASS: HTTP status 502"
else
  echo "FAIL: Expected HTTP 502, got $RESP_CODE"
  FAILED=1
fi

FAILURE_CODE=$(json_code "$RESP_BODY")
if [ "$FAILURE_CODE" = "50201" ]; then
  echo "PASS: Response code=50201"
else
  echo "FAIL: Expected code=50201, got code=$FAILURE_CODE"
  FAILED=1
fi

SUCCESS_FLAG=$(echo "$RESP_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$SUCCESS_FLAG" = "False" ] || [ "$SUCCESS_FLAG" = "false" ]; then
  echo "PASS: success=false"
else
  echo "FAIL: Expected success=false, got success=$SUCCESS_FLAG"
  FAILED=1
fi

# ════════════════════════════════════════════════════════════
# Restore: Back to success mode
# ════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 2: Restore success mode ==="
echo "Attempting restore..."

cd "$COMPOSE_DIR"
CRAG_QUERY_LLM_STUB_MODE=success docker compose up -d --build app
echo "App rebuild initiated (success mode restore)"

# Wait for restored app
echo "--- Wait for restored app readiness ---"
if ! wait_for_app "restored success mode"; then
  echo "CRITICAL: environment may be in failure mode"
  FAILED=1
fi

# Confirm success mode works
echo ""
echo "--- Confirm success mode ---"
http_post_raw "$BASE_URL/api/v1/query" '{"question":"测试确认问题"}' "Query in restored success mode"
RESTORE_CODE=$(json_code "$RESP_BODY")
if [ "$RESTORE_CODE" = "0" ]; then
  echo "PASS: Restored success mode confirmed (code=0)"
else
  echo "FAIL: Restored app returned code=$RESTORE_CODE (expected 0)"
  FAILED=1
fi

# ── Final ──
echo ""
echo "=== Test data preserved with RUN_ID=$RUN_ID ==="
if [ "$FAILED" -eq 0 ]; then
  echo "=== Query Stub Failure HTTP Regression PASSED ==="
  exit 0
else
  echo "=== Query Stub Failure HTTP Regression FAILED ==="
  exit 1
fi
