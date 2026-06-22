#!/bin/bash
# CRAG-Demo — DeepSeek Anthropic API Acceptance Test
# 验证真实 DeepSeek V4 Flash Anthropic 兼容 API 的 Query 全链路.
#
# 前置条件:
#   - DEEPSEEK_API_KEY 已在宿主环境变量中设置（临时注入，不写入任何文件）
#   - Docker Compose 服务正在运行
#
# 用法: DEEPSEEK_API_KEY=<key> bash scripts/tests/http/query_deepseek_acceptance_test.sh [BASE_URL]
#       BASE_URL 默认 CRAG_RAG_BASE_URL 环境变量或 http://localhost:8082
#
# 安全约束:
#   - 凭据只从宿主环境变量 DEEPSEEK_API_KEY 临时注入，禁止写入 .env、脚本、Plan 或验收记录
#   - 仅执行一次真实 DeepSeek API 调用；数据写入与索引等待在 Stub 模式下完成
#   - 不保存完整响应、Prompt、Context 或认证信息到日志
#   - 验收后恢复 Stub success 模式

set -euo pipefail

BASE_URL="${1:-${CRAG_RAG_BASE_URL:-http://localhost:8082}}"
RUN_ID="deepseek-accept-$(date +%s)-$$"
VERIFICATION_CODE="deepseek-verify-${RUN_ID}-xyz789"
FAILED=0
COMPOSE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
_STUB_RESTORED=0

# ── Trap: ensure Stub success mode is restored on ANY exit path ──
restore_stub_on_exit() {
  if [ "$_STUB_RESTORED" -eq 1 ]; then
    return
  fi
  _STUB_RESTORED=1
  echo ""
  echo "=== Trap: restoring Stub success mode ==="
  cd "$COMPOSE_DIR" || return
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
  CRAG_QUERY_LLM_PROVIDER=stub \
  CRAG_QUERY_LLM_STUB_MODE=success \
  docker compose up -d --build rag-service 2>/dev/null || true
  wait_for_app "restored stub (trap)" 2>/dev/null || true
}
trap restore_stub_on_exit EXIT

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

# ── Helper: wait for rag-service to respond to queries ──
wait_for_app() {
  local label="$1"
  local max_wait=120
  local waited=0
  echo "  Waiting for rag-service to be ready ($label)..."
  while [ $waited -lt $max_wait ]; do
    sleep 5
    waited=$((waited + 5))
    local resp
    resp=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$BASE_URL/api/v1/query" 2>/dev/null || echo "000")
    if [[ "$resp" =~ ^[245][0-9][0-9]$ ]]; then
      echo "  rag-service ready after ${waited}s (HTTP $resp)"
      return 0
    fi
    echo "  Waiting... ${waited}s / ${max_wait}s"
  done
  echo "FAIL: rag-service did not become ready within ${max_wait}s"
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
# Phase 1: Build with Stub, write test data, wait for indexing
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 1: Build rag-service with Stub, write data and wait for indexing ==="

cd "$COMPOSE_DIR"
DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY}" \
CRAG_QUERY_LLM_PROVIDER=stub \
CRAG_QUERY_LLM_STUB_MODE=success \
docker compose up -d --build rag-service

echo "rag-service rebuild initiated with provider=stub (indexing phase)"

# Wait for app to be ready
echo "--- Wait for rag-service readiness (stub mode) ---"
if ! wait_for_app "stub mode"; then
  echo "FAIL: App did not become ready in stub mode — aborting"
  FAILED=1
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: BLOCKED"
  exit 2
fi

# ── Phase 1a: Write test data via AdminRag ──
echo ""
echo "--- Phase 1a: Write test data via AdminRag (stub mode) ---"
http_post "$BASE_URL/api/v1/admin/rag" \
  "{\"title\":\"deepseek-accept-${RUN_ID}\",\"content\":\"${VERIFICATION_CODE} CRAG-Demo 是一个基于 RAG 的问答机器人，使用 PostgreSQL 数据库和 pgvector 向量扩展进行混合检索。\"}" \
  "AdminRag write"

WRITE_CODE=$(json_code "$RESP_BODY")
PARENT_CHUNK_ID=""
if [ "$WRITE_CODE" = "0" ]; then
  PARENT_CHUNK_ID=$(echo "$RESP_BODY" | python3 -c "import sys,json; r=json.load(sys.stdin); ids=r['result']['parentChunkIds']; print(ids[0] if ids else '')" 2>/dev/null || echo "")
  echo "PASS: AdminRag write success, parentChunkId=$PARENT_CHUNK_ID"
else
  echo "FAIL: AdminRag returned code=$WRITE_CODE, resp=$RESP_BODY"
  FAILED=1
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: FAILED"
  exit 1
fi

# ── Phase 1b: Poll Query API in Stub mode until target chunk is indexed ──
echo ""
echo "--- Phase 1b: Wait for indexing (polling via Stub — zero DeepSeek cost) ---"

MAX_INDEX_ATTEMPTS=30  # 30 attempts * 3s = 90s max
INDEX_ATTEMPT=0
INDEXED=0

while [ $INDEX_ATTEMPT -lt $MAX_INDEX_ATTEMPTS ]; do
  INDEX_ATTEMPT=$((INDEX_ATTEMPT + 1))
  sleep 3

  # Poll query — Stub mode, so NO real LLM call, but Retrieval + sources are real
  INDEX_RESP=$(curl -s -X POST "$BASE_URL/api/v1/query" \
    -H "Content-Type: application/json" \
    -d "{\"question\":\"${VERIFICATION_CODE} 使用什么数据库？\"}" || echo '{"code":-1}')
  INDEX_CODE=$(json_code "$INDEX_RESP")

  if [ "$INDEX_CODE" = "0" ]; then
    TARGET_IN_SOURCES=$(echo "$INDEX_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
target = '${PARENT_CHUNK_ID}'
found = False
for src in sources:
    if src.get('parentChunkId', '') == target:
        found = True
        break
    if target in src.get('matchedChildIds', []):
        found = True
        break
print('FOUND' if found else 'WAITING')
" 2>/dev/null || echo "WAITING")
    if [ "$TARGET_IN_SOURCES" = "FOUND" ]; then
      echo "Indexing complete after ${INDEX_ATTEMPT} attempts (target chunk indexed)"
      INDEXED=1
      break
    fi
  fi

  echo "  Waiting for indexing... attempt ${INDEX_ATTEMPT}/${MAX_INDEX_ATTEMPTS}"
done

if [ "$INDEXED" -eq 0 ]; then
  echo "FAIL: Data did not index within ${MAX_INDEX_ATTEMPTS} attempts — aborting"
  FAILED=1
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: FAILED"
  exit 1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 2: Rebuild app with DeepSeek provider
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 2: Rebuild rag-service with DeepSeek provider ==="

cd "$COMPOSE_DIR"
DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY}" \
CRAG_QUERY_LLM_PROVIDER=deepseek \
docker compose up -d --build rag-service

echo "rag-service rebuild initiated with provider=deepseek"

# Wait for app to be ready
echo "--- Wait for rag-service readiness (deepseek mode) ---"
if ! wait_for_app "deepseek mode"; then
  echo "FAIL: App did not become ready after DeepSeek rebuild — aborting"
  FAILED=1
  echo ""
  echo "=== Acceptance Test Complete ==="
  echo "RUN_ID=$RUN_ID"
  echo "Result: BLOCKED"
  exit 2
fi

# ═══════════════════════════════════════════════════════════════
# Phase 3: Single DeepSeek Query (exactly ONE real API call)
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 3: Execute single DeepSeek query (exactly 1 real API call) ==="

QUERY_RAW=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/query" \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"${VERIFICATION_CODE} CRAG-Demo 使用什么数据库？\"}" || printf '{"code":-1}\n000')

QUERY_HTTP=$(printf '%s' "$QUERY_RAW" | tail -1)
QUERY_RESP=$(printf '%s' "$QUERY_RAW" | sed '$d')
echo "DeepSeek query complete — HTTP $QUERY_HTTP"

if [ "$QUERY_HTTP" != "200" ]; then
  echo "FAIL: DeepSeek query returned HTTP $QUERY_HTTP (expected 200)"
  FAILED=1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 4: Verify Query response
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 4: Verify query response ==="

# 4a. Check code=0
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
truncated = answer[:200] + ('...' if len(answer) > 200 else '')
print(repr(truncated))
" 2>/dev/null || echo "''")
echo "Answer (repr, truncated 200): $ANSWER_REPR"

# 4d. Answer contains the unique VERIFICATION_CODE — proves LLM processed our specific data
ANSWER_HAS_CODE=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
answer = resp.get('result', {}).get('answer', '')
verification = '${VERIFICATION_CODE}'
print('OK' if verification in answer else 'MISSING')
" 2>/dev/null || echo "ERROR")
if [ "$ANSWER_HAS_CODE" = "OK" ]; then
  echo "PASS: Answer contains unique VERIFICATION_CODE"
else
  echo "FAIL: Answer does not contain VERIFICATION_CODE — model did not process specific test data"
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

# 4i. Target source exists with valid reference AND non-empty matchedChildIds
#     ok_ref and ok_matched are now computed AND enforced in the judgment
#     Use if/else to safely capture exit code under set -e
if TARGET_SOURCE_OUTPUT=$(echo "$QUERY_RESP" | python3 -c "
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
        print(f'FOUND ref={ref} ok_ref={ok_ref} ok_matched={ok_matched} matchedChildIds={matched}')
        sys.exit(0 if (ok_ref and ok_matched) else 1)
print('NOT_FOUND')
sys.exit(1)
" 2>/dev/null); then
  TARGET_SOURCE_EXIT=0
else
  TARGET_SOURCE_EXIT=$?
fi

if [ "$TARGET_SOURCE_EXIT" -eq 0 ]; then
  echo "PASS: Target source found with valid reference and non-empty matchedChildIds"
  echo "  $TARGET_SOURCE_OUTPUT"
else
  if echo "$TARGET_SOURCE_OUTPUT" | grep -q "^FOUND"; then
    echo "FAIL: Target source found but reference or matchedChildIds invalid"
    echo "  $TARGET_SOURCE_OUTPUT"
  else
    echo "FAIL: Target source (parentChunkId=$PARENT_CHUNK_ID) not found in sources"
  fi
  FAILED=1
fi

# ═══════════════════════════════════════════════════════════════
# Phase 5: Inspect container logs (sanitized supplementary evidence)
# ═══════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 5: Container logs (sanitized supplementary evidence) ==="

echo "--- provider=deepseek ---"
docker logs crag-rag-service 2>&1 | grep -i "provider=deepseek" | tail -5 || echo "No provider=deepseek log lines found"

echo "--- protocol=anthropic ---"
docker logs crag-rag-service 2>&1 | grep -i "protocol=anthropic\|anthropic" | tail -5 || echo "No anthropic protocol log lines found"

echo "--- model ---"
docker logs crag-rag-service 2>&1 | grep -i "model=" | tail -5 || echo "No model log lines found"

echo "--- usage ---"
docker logs crag-rag-service 2>&1 | grep -i "usage" | tail -5 || echo "No usage log lines found"

echo "--- request_id/result/total_time_ms ---"
docker logs crag-rag-service 2>&1 | grep -E "(requestId|result=|totalTime)" | tail -10 || echo "No request_id/result/totalTime log lines found"

# Safety check: ensure NO sensitive data in logs
# Prohibited: API keys, auth headers, full Context/Prompt, full response, unique test content
echo "--- Safety: scan for prohibited patterns in logs ---"
SAFETY_FAILED=0
ALL_LOGS=$(docker logs crag-rag-service 2>&1 || true)

# Check 1: Auth credentials
AUTH_LEAK=$(echo "$ALL_LOGS" | grep -ciE "(x-api-key|authorization|api_key|deepseek_api_key)" || true)
if [ "$AUTH_LEAK" -ne 0 ]; then
  echo "FAIL: Found $AUTH_LEAK potential credential leakage(s) in container logs"
  SAFETY_FAILED=1
else
  echo "PASS: No API key or auth header leakage detected"
fi

# Check 2: Unique test content (VERIFICATION_CODE, RUN_ID must not leak into logs)
CONTENT_LEAK=$(echo "$ALL_LOGS" | grep -cF "$VERIFICATION_CODE" || true)
if [ "$CONTENT_LEAK" -ne 0 ]; then
  echo "FAIL: VERIFICATION_CODE found in logs ($CONTENT_LEAK occurrence(s)) — unique test content must not leak"
  SAFETY_FAILED=1
else
  echo "PASS: VERIFICATION_CODE not found in logs"
fi

# Check 3: Context boundary markers (must not appear in logs)
CONTEXT_LEAK=$(echo "$ALL_LOGS" | grep -ciE "<CRAG:[a-f0-9]{6}:S[0-9]+>" || true)
if [ "$CONTEXT_LEAK" -ne 0 ]; then
  echo "FAIL: Context boundary markers found in logs ($CONTEXT_LEAK occurrence(s))"
  SAFETY_FAILED=1
else
  echo "PASS: No Context boundary markers in logs"
fi

# Check 4: Prompt structural patterns (must not appear in logs)
PROMPT_LEAK=$(echo "$ALL_LOGS" | grep -ciE "(system prompt|SystemMessage|user prompt|UserMessage|system_prompt|user_prompt)" || true)
if [ "$PROMPT_LEAK" -ne 0 ]; then
  echo "FAIL: Prompt structural content found in logs ($PROMPT_LEAK occurrence(s))"
  SAFETY_FAILED=1
else
  echo "PASS: No Prompt structural content in logs"
fi

if [ "$SAFETY_FAILED" -ne 0 ]; then
  FAILED=1
  echo "FAIL: Safety scan detected prohibited content in container logs"
else
  echo "PASS: All safety checks passed"
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
docker compose up -d --build rag-service
echo "rag-service rebuild initiated (stub success mode restore)"

# Wait for restored app
echo "--- Wait for restored rag-service readiness ---"
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
  _STUB_RESTORED=1
else
  echo "FAIL: Restored rag-service returned code=$RESTORE_CODE (expected 0)"
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
