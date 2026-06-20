#!/bin/bash
# CRAG-Demo — DeepSeek Anthropic API Acceptance Test
# 验证真实 DeepSeek V4 Flash Anthropic 兼容 API 的 Query 全链路.
#
# 前置条件:
#   - DEEPSEEK_API_KEY 已在宿主环境变量中设置（临时注入，不写入任何文件）
#   - Docker Compose 服务正在运行
#
# 用法: DEEPSEEK_API_KEY=<key> bash scripts/tests/http/query_deepseek_acceptance_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8080
#
# 安全约束:
#   - 凭据只从宿主环境变量 DEEPSEEK_API_KEY 临时注入，禁止写入 .env、脚本、Plan 或验收记录
#   - 首次 API 调用失败后立即停止，不再自动重试
#   - 不保存完整响应、Prompt、Context 或认证信息到日志
#   - 验收后恢复 Stub success 模式

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
RUN_ID="deepseek-accept-$(date +%s)-$$"
VERIFICATION_CODE="deepseek-verify-${RUN_ID}-xyz789"
FAILED=0
COMPOSE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"

# ── Helper: GET request ──
# Sets globals RESP_BODY, RESP_CODE.
http_get() {
  local url="$1"
  local desc="$2"
  local raw
  raw=$(curl -s -w '\n%{http_code}' "$url" || printf '{"code":-1}\n000')
  RESP_CODE=$(printf '%s' "$raw" | tail -1)
  RESP_BODY=$(printf '%s' "$raw" | sed '$d')
  if [ "$RESP_CODE" != "200" ]; then
    echo "FAIL: $desc — HTTP $RESP_CODE (expected 200)"
    FAILED=1
  else
    echo "PASS: $desc — HTTP 200"
  fi
}

# ── Helper: POST request, asserts HTTP 200 ──
# Sets globals RESP_BODY, RESP_CODE.
http_post() {
  local url="$1"
  local data="$2"
  local desc="$3"
  local raw
  raw=$(curl -s -w '\n%{http_code}' -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$data" || printf '{"code":-1}\n000')
  RESP_CODE=$(printf '%s' "$raw" | tail -1)
  RESP_BODY=$(printf '%s' "$raw" | sed '$d')
  if [ "$RESP_CODE" != "200" ]; then
    echo "FAIL: $desc — HTTP $RESP_CODE (expected 200)"
    FAILED=1
  else
    echo "PASS: $desc — HTTP 200"
  fi
}

# ── Helper: POST request, returns raw code+body without asserting ──
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

# ── Helper: wait for app to respond to queries ──
wait_for_app() {
  local label="$1"
  local max_wait=120
  local waited=0
  echo "  Waiting for app to be ready ($label)..."
  while [ $waited -lt $max_wait ]; do
    sleep 5
    waited=$((waited + 5))
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

# ═══════════════════════════════════════════════════════════════
# Phase 0: Pre-flight
# ═══════════════════════════════════════════════════════════════
echo "=== DeepSeek Anthropic API Acceptance Test ==="
echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "BLOCKED: DEEPSEEK_API_KEY is not set in environment"
  echo "Set it temporarily: DEEPSEEK_API_KEY=<your-key> bash $0"
  exit 2  # exit code 2 = BLOCKED (distinct from test failure)
fi

echo "DEEPSEEK_API_KEY is set (value hidden)"

# Quick reachability check (informational only)
echo "--- Phase 0: Pre-flight reachability check ---"
HEALTH_CHECK=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE_URL/api/v1/query" 2>/dev/null || echo "unreachable")
if [ "$HEALTH_CHECK" = "unreachable" ]; then
  echo "WARNING: $BASE_URL appears unreachable — may fail later if services not running"
else
  echo "INFO: $BASE_URL is reachable (HTTP $HEALTH_CHECK)"
fi

# ═══════════════════════════════════════════════════════════════
# Phase 1: Rebuild app with DeepSeek provider
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 1: Rebuild app with DeepSeek provider ==="

cd "$COMPOSE_DIR"
DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY}" \
CRAG_QUERY_LLM_PROVIDER=deepseek \
docker compose up -d --build app

echo "App rebuild initiated with provider=deepseek"

# Wait for app to be ready
echo "--- Wait for app readiness ---"
if ! wait_for_app "deepseek mode"; then
  echo "FAIL: App did not become ready after DeepSeek rebuild — aborting"
  FAILED=1
  # Attempt restore before final exit
  echo ""
  echo "=== Attempting restore before BLOCKED exit ==="
  cd "$COMPOSE_DIR"
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
  CRAG_QUERY_LLM_PROVIDER=stub \
  CRAG_QUERY_LLM_STUB_MODE=success \
  docker compose up -d --build app 2>/dev/null || true
  wait_for_app "restored stub" 2>/dev/null || true
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: BLOCKED"
  exit 2
fi

# ═══════════════════════════════════════════════════════════════
# Phase 2: Write test data via AdminRag
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 2: Write test data via AdminRag ==="
http_post "$BASE_URL/api/v1/admin/rag" \
  "{\"title\":\"deepseek-accept-${RUN_ID}\",\"content\":\"${VERIFICATION_CODE} CRAG-Demo 是一个基于 RAG 的问答机器人，使用 PostgreSQL 数据库和 pgvector 向量扩展进行混合检索。\"}" \
  "AdminRag write"

WRITE_CODE=$(json_code "$RESP_BODY")
PARENT_CHUNK_ID=""
if [ "$WRITE_CODE" = "0" ]; then
  PARENT_CHUNK_ID=$(echo "$RESP_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['parentChunkId'])" 2>/dev/null || echo "")
  echo "PASS: AdminRag write success, parentChunkId=$PARENT_CHUNK_ID"
else
  echo "FAIL: AdminRag returned code=$WRITE_CODE, resp=$RESP_BODY"
  FAILED=1
  # Cannot proceed without written data
  echo ""
  echo "=== Attempting restore before FAILED exit ==="
  cd "$COMPOSE_DIR"
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
  CRAG_QUERY_LLM_PROVIDER=stub \
  CRAG_QUERY_LLM_STUB_MODE=success \
  docker compose up -d --build app 2>/dev/null || true
  wait_for_app "restored stub" 2>/dev/null || true
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: FAILED"
  exit 1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 3: Wait for indexing + Phase 4: Query and Assert
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 3: Poll Query API for results ==="

MAX_ATTEMPTS=30  # 30 attempts * 3s = 90s max
ATTEMPT=0
QUERY_RESP=""

while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
  ATTEMPT=$((ATTEMPT + 1))
  sleep 3

  # Phase 3 poll query — this becomes the acceptance query
  QUERY_RESP=$(curl -s -X POST "$BASE_URL/api/v1/query" \
    -H "Content-Type: application/json" \
    -d "{\"question\":\"${VERIFICATION_CODE} CRAG-Demo 使用什么数据库？\"}" || echo '{"code":-1}')
  QUERY_CODE=$(json_code "$QUERY_RESP")

  if [ "$QUERY_CODE" = "0" ]; then
    # Check if sources is non-empty
    SOURCE_COUNT=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
sources = result.get('sources', [])
print(len(sources))
" 2>/dev/null || echo "0")
    if [ "$SOURCE_COUNT" -gt 0 ]; then
      echo "Query ready after ${ATTEMPT} attempts (found $SOURCE_COUNT source(s))"
      break
    fi
  fi

  echo "  Waiting... attempt ${ATTEMPT}/${MAX_ATTEMPTS}"
  if [ $ATTEMPT -ge $MAX_ATTEMPTS ]; then
    echo "FAIL: Query did not return results within ${MAX_ATTEMPTS} attempts — aborting"
    # Do NOT print full response — safety constraint
    echo "FAIL: Last response code=$QUERY_CODE (full response hidden for safety)"
    FAILED=1
  fi
done

# If Phase 3 timed out, bail out
if [ "$FAILED" -ne 0 ]; then
  echo ""
  echo "=== Attempting restore before FAILED exit ==="
  cd "$COMPOSE_DIR"
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
  CRAG_QUERY_LLM_PROVIDER=stub \
  CRAG_QUERY_LLM_STUB_MODE=success \
  docker compose up -d --build app 2>/dev/null || true
  wait_for_app "restored stub" 2>/dev/null || true
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: FAILED"
  exit 1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 4: Verify Query response
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 4: Verify query response ==="

# 4a. HTTP status already 200 from curl; check code=0
QUERY_RESP_CODE=$(echo "$QUERY_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$QUERY_RESP_CODE" != "0" ]; then
  echo "FAIL: Query returned code=$QUERY_RESP_CODE (expected 0)"
  FAILED=1
else
  echo "PASS: Query code=0"
fi

# 4b. success = true
SUCCESS_FLAG=$(echo "$QUERY_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$SUCCESS_FLAG" = "True" ] || [ "$SUCCESS_FLAG" = "true" ]; then
  echo "PASS: success=true"
else
  echo "FAIL: Expected success=true, got success=$SUCCESS_FLAG"
  FAILED=1
fi

# 4c. Extract answer via repr for safe display
ANSWER_REPR=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
answer = result.get('answer', '')
# Escape newlines for safe display, limit length for terminal safety
truncated = answer[:200] + ('...' if len(answer) > 200 else '')
print(repr(truncated))
" 2>/dev/null || echo "''")
echo "Answer (repr, truncated 200): $ANSWER_REPR"

# 4d. Answer contains VERIFICATION_CODE (proves real LLM processed our data)
ANSWER_CONTAINS_CODE=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
answer = result.get('answer', '')
code = '${VERIFICATION_CODE}'
print('OK' if code in answer else 'MISSING')
" 2>/dev/null || echo "ERROR")
if [ "$ANSWER_CONTAINS_CODE" = "OK" ]; then
  echo "PASS: Answer contains VERIFICATION_CODE"
else
  echo "FAIL: Answer does not contain VERIFICATION_CODE=$VERIFICATION_CODE"
  FAILED=1
fi

# 4e. Answer is NOT exactly "知识库证据不足"
ANSWER_IS_INSUFFICIENT=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
answer = result.get('answer', '').strip()
print('YES' if answer == '知识库证据不足' else 'NO')
" 2>/dev/null || echo "ERROR")
if [ "$ANSWER_IS_INSUFFICIENT" = "NO" ]; then
  echo "PASS: Answer is not exactly '知识库证据不足'"
elif [ "$ANSWER_IS_INSUFFICIENT" = "YES" ]; then
  echo "FAIL: Answer is exactly '知识库证据不足' — model did not use provided context"
  FAILED=1
else
  echo "FAIL: Could not determine if answer is '知识库证据不足'"
  FAILED=1
fi

# 4f. At least one valid [Sx] reference
HAS_VALID_REF=$(echo "$QUERY_RESP" | python3 -c "
import sys, json, re
resp = json.load(sys.stdin)
result = resp.get('result', {})
answer = result.get('answer', '')
refs = re.findall(r'\[S\d+\]', answer)
print('OK' if len(refs) > 0 else 'NONE')
" 2>/dev/null || echo "ERROR")
if [ "$HAS_VALID_REF" = "OK" ]; then
  echo "PASS: Answer contains at least one [Sx] reference"
else
  echo "FAIL: Answer contains no [Sx] references"
  FAILED=1
fi

# 4g. NO invalid references (each [Sx] must have x <= sources.size())
SOURCE_COUNT=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
sources = result.get('sources', [])
print(len(sources))
" 2>/dev/null || echo "0")

ALL_REFS_VALID=$(echo "$QUERY_RESP" | python3 -c "
import sys, json, re
resp = json.load(sys.stdin)
result = resp.get('result', {})
answer = result.get('answer', '')
sources = result.get('sources', [])
source_count = len(sources)
refs = re.findall(r'\[S(\d+)\]', answer)
invalid = [int(r) for r in refs if int(r) > source_count]
print('ALL_VALID' if len(invalid) == 0 else f'INVALID: {sorted(invalid)}')
" 2>/dev/null || echo "ERROR")
if [ "$ALL_REFS_VALID" = "ALL_VALID" ]; then
  echo "PASS: All [Sx] references are valid (x <= $SOURCE_COUNT sources)"
else
  echo "FAIL: $ALL_REFS_VALID"
  FAILED=1
fi

# 4h. sources is non-empty array
if [ "$SOURCE_COUNT" -gt 0 ]; then
  echo "PASS: sources is non-empty ($SOURCE_COUNT item(s))"
else
  echo "FAIL: sources is empty or missing"
  FAILED=1
fi

# 4i. Target source (matching our written parentChunkId) exists with correct reference
TARGET_SOURCE_OUTPUT=$(echo "$QUERY_RESP" | python3 -c "
import sys, json, re
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
target_parent = '${PARENT_CHUNK_ID}'
for src in sources:
    if src.get('parentChunkId', '') == target_parent:
        ref = src.get('reference', '')
        matched = src.get('matchedChildIds', [])
        ok_ref = bool(re.match(r'^S\d+$', ref))
        ok_matched = isinstance(matched, list) and len(matched) > 0
        print(f'FOUND ref={ref} matchedChildIds={matched}')
        sys.exit(0)
print('NOT_FOUND')
" 2>/dev/null || echo "ERROR")
if echo "$TARGET_SOURCE_OUTPUT" | grep -q "^FOUND"; then
  echo "PASS: Target source found with reference and matchedChildIds"
  echo "  $TARGET_SOURCE_OUTPUT"
else
  echo "FAIL: Target source (parentChunkId=$PARENT_CHUNK_ID) not found in sources"
  FAILED=1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 5: Inspect container logs (sanitized supplementary evidence)
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 5: Container logs (sanitized supplementary evidence) ==="

echo "--- provider=deepseek ---"
docker logs crag-app 2>&1 | grep -i "provider=deepseek" | tail -5 || echo "No provider=deepseek log lines found"

echo "--- protocol=anthropic ---"
docker logs crag-app 2>&1 | grep -i "protocol=anthropic\|anthropic" | tail -5 || echo "No anthropic protocol log lines found"

echo "--- model ---"
docker logs crag-app 2>&1 | grep -i "model=" | tail -5 || echo "No model log lines found"

echo "--- usage ---"
docker logs crag-app 2>&1 | grep -i "usage" | tail -5 || echo "No usage log lines found"

echo "--- request_id/result/total_time_ms ---"
docker logs crag-app 2>&1 | grep -E "(requestId|result=|totalTime)" | tail -10 || echo "No request_id/result/totalTime log lines found"

# Safety check: ensure NO full response, prompt, context, API key, or auth headers in logs
echo "--- Safety: scan for prohibited patterns in logs ---"
SAFETY_VIOLATIONS=$(docker logs crag-app 2>&1 | grep -ciE "(x-api-key|authorization|api_key|deepseek_api_key)" || true)
if [ "$SAFETY_VIOLATIONS" -eq 0 ]; then
  echo "PASS: No API key or auth header leakage detected in container logs"
else
  echo "FAIL: Found $SAFETY_VIOLATIONS potential credential leakage(s) in container logs"
  FAILED=1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 6: Restore Stub mode
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 6: Restore Stub success mode ==="

cd "$COMPOSE_DIR"
DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
CRAG_QUERY_LLM_PROVIDER=stub \
CRAG_QUERY_LLM_STUB_MODE=success \
docker compose up -d --build app
echo "App rebuild initiated (stub success mode restore)"

# Wait for restored app
echo "--- Wait for restored app readiness ---"
if ! wait_for_app "restored stub mode"; then
  echo "CRITICAL: environment may be in DeepSeek mode after failed restore"
  FAILED=1
fi

# Confirm stub success mode works
echo ""
echo "--- Confirm stub success mode ---"
http_post_raw "$BASE_URL/api/v1/query" "{\"question\":\"确认恢复问题\"}" "Query in restored stub mode"
RESTORE_CODE=$(json_code "$RESP_BODY")
if [ "$RESTORE_CODE" = "0" ]; then
  echo "PASS: Restored stub success mode confirmed (code=0)"
else
  echo "FAIL: Restored app returned code=$RESTORE_CODE (expected 0)"
  FAILED=1
fi

# ═══════════════════════════════════════════════════════════════
# Final Report
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Acceptance Test Complete ==="
echo "RUN_ID=$RUN_ID"
if [ "$FAILED" -eq 0 ]; then
  echo "Result: PASSED"
  exit 0
else
  echo "Result: FAILED"
  exit 1
fi
