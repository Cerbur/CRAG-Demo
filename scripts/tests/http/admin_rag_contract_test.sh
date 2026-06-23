#!/bin/bash
# CRAG-Demo AdminRag HTTP 契约回归测试
# 验证正式 AdminRag HTTP 入口的成功、Bean Validation 与未知路径响应。
#
# 用法: bash scripts/tests/http/admin_rag_contract_test.sh [BASE_URL]
#       BASE_URL 默认 CRAG_RAG_BASE_URL 环境变量或 http://localhost:8082
#
# 每次运行生成唯一 runId，写入可追踪测试数据。
# 当前无安全精确删除入口，测试数据保留在数据库，不执行清理。

set -euo pipefail

BASE_URL="${1:-${CRAG_RAG_BASE_URL:-http://localhost:8082}}"
FAILED=0
RUN_ID="contract-$(date +%Y%m%d-%H%M%S)-$$"

echo "=== AdminRag Contract Test ==="
echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"
echo ""

# ── 辅助函数 ──

assert_status() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "PASS: $desc → HTTP $actual"
  else
    echo "FAIL: $desc → expected HTTP $expected, got HTTP $actual"
    FAILED=1
  fi
}

assert_json() {
  local desc="$1" path="$2" expected="$3" actual="$4"
  if [ "$actual" = "$expected" ]; then
    echo "PASS: $desc → $path = $actual"
  else
    echo "FAIL: $desc → expected $path=$expected, got $actual"
    FAILED=1
  fi
}

# ── 1. AdminRag 成功 ──

echo "--- 1. AdminRag Success ---"

TITLE="contract-test-$RUN_ID"
CONTENT="Contract test content for $RUN_ID"

RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "$BASE_URL/api/v1/admin/rag" \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"$TITLE\", \"content\": \"$CONTENT\"}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

assert_status "AdminRag success" "200" "$HTTP_CODE"

SUCCESS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['success'])" 2>/dev/null || echo "PARSE_ERROR")
CODE=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "PARSE_ERROR")
DOC_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" 2>/dev/null || echo "PARSE_ERROR")
CHUNKS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['chunks'])" 2>/dev/null || echo "PARSE_ERROR")
STATUS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['status'])" 2>/dev/null || echo "PARSE_ERROR")
TOP_KEYS=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(','.join(sorted(d.keys())))" 2>/dev/null || echo "PARSE_ERROR")
RESULT_KEYS=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin)['result']; print(','.join(sorted(d.keys())))" 2>/dev/null || echo "PARSE_ERROR")

assert_json "AdminRag success field" "success" "True" "$SUCCESS"
assert_json "AdminRag code" "code" "0" "$CODE"
assert_json "AdminRag top-level keys" "top-level keys" "code,result,success" "$TOP_KEYS"
assert_json "AdminRag result keys" "result keys" "chunks,docId,parentChunkIds,status" "$RESULT_KEYS"

# 验证 result 含 docId（decimal string 格式）且非空
if [ "$DOC_ID" = "PARSE_ERROR" ] || [ -z "$DOC_ID" ]; then
  echo "FAIL: AdminRag result.docId → missing or parse error"
  FAILED=1
elif echo "$DOC_ID" | grep -Eq '^[0-9]+$'; then
  echo "PASS: AdminRag result.docId → $DOC_ID (decimal string)"
else
  echo "FAIL: AdminRag result.docId → not a decimal string: $DOC_ID"
  FAILED=1
fi

# chunks 应为正整数（含0）
if [ "$CHUNKS" = "PARSE_ERROR" ]; then
  echo "FAIL: AdminRag result.chunks → parse error"
  FAILED=1
else
  echo "PASS: AdminRag result.chunks → $CHUNKS"
fi

# status 应为 PENDING
assert_json "AdminRag status" "result.status" "PENDING" "$STATUS"

# 验证 parentChunkIds 每项是 decimal string
PARENT_CHUNK_IDS=$(echo "$BODY" | python3 -c "import sys,json; print(' '.join(json.load(sys.stdin)['result'].get('parentChunkIds', [])))" 2>/dev/null || echo "PARSE_ERROR")
if [ "$PARENT_CHUNK_IDS" = "PARSE_ERROR" ]; then
  echo "FAIL: AdminRag result.parentChunkIds → parse error"
  FAILED=1
else
  ALL_DECIMAL=1
  for PID in $PARENT_CHUNK_IDS; do
    if echo "$PID" | grep -Eq '^[0-9]+$'; then
      :
    else
      echo "FAIL: AdminRag parentChunkId → not a decimal string: $PID"
      FAILED=1
      ALL_DECIMAL=0
    fi
  done
  if [ "$ALL_DECIMAL" -eq 1 ]; then
    echo "PASS: AdminRag parentChunkIds all decimal strings → $PARENT_CHUNK_IDS"
  fi
fi

echo ""

# ── 2. Bean Validation 失败 ──

echo "--- 2. Bean Validation Failure ---"

VALIDATION_RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "$BASE_URL/api/v1/admin/rag" \
  -H "Content-Type: application/json" \
  -d '{}')

VAL_HTTP=$(echo "$VALIDATION_RESPONSE" | tail -1)
VAL_BODY=$(echo "$VALIDATION_RESPONSE" | sed '$d')

assert_status "Bean Validation" "400" "$VAL_HTTP"

VAL_CODE=$(echo "$VAL_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "PARSE_ERROR")
VAL_SUCCESS=$(echo "$VAL_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['success'])" 2>/dev/null || echo "PARSE_ERROR")

assert_json "Bean Validation code" "code" "40001" "$VAL_CODE"
assert_json "Bean Validation success" "success" "False" "$VAL_SUCCESS"

echo ""

# ── 3. 未知路径 ──

echo "--- 3. Unknown Path ---"

NOTFOUND_RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/nonexistent-$RUN_ID")

NF_HTTP=$(echo "$NOTFOUND_RESPONSE" | tail -1)
NF_BODY=$(echo "$NOTFOUND_RESPONSE" | sed '$d')

assert_status "Unknown path" "404" "$NF_HTTP"

NF_CODE=$(echo "$NF_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "PARSE_ERROR")
NF_SUCCESS=$(echo "$NF_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['success'])" 2>/dev/null || echo "PARSE_ERROR")

assert_json "Unknown path code" "code" "40401" "$NF_CODE"
assert_json "Unknown path success" "success" "False" "$NF_SUCCESS"

echo ""

# ── 结果 ──

if [ "$FAILED" -eq 0 ]; then
  echo "=== AdminRag Contract Test PASSED (runId=$RUN_ID) ==="
  exit 0
else
  echo "=== AdminRag Contract Test FAILED (runId=$RUN_ID) ==="
  exit 1
fi
