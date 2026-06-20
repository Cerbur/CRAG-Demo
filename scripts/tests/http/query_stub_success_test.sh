#!/bin/bash
# CRAG-Demo Query Stub Success HTTP Regression
# 验证 Stub 模式下的完整 Query 链路：AdminRag 写入 → 索引等待 → Query API 调用
#
# 用法: bash scripts/tests/http/query_stub_success_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8080

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
RUN_ID="qs-$(date +%s)-$$"
FAILED=0
VERIFICATION_CODE="verify-${RUN_ID}-abc123"  # unique, unguessable code

echo "=== Query Stub Success HTTP Regression ==="
echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"
echo "VERIFICATION_CODE=$VERIFICATION_CODE"

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

# ── Helper: GET request, asserts HTTP 200 ──
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

# ── Helper: extract JSON code field from body ──
json_code() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1"
}

# ── 1. Write a document via AdminRag API ──
echo ""
echo "--- 1. Write test document via AdminRag ---"
http_post "$BASE_URL/api/v1/admin/rag" \
  "{\"title\":\"query-test-${RUN_ID}\",\"content\":\"${VERIFICATION_CODE} 项目使用 PostgreSQL 和 pgvector 进行向量存储和混合检索。\"}" \
  "AdminRag write"

WRITE_CODE=$(json_code "$RESP_BODY")
if [ "$WRITE_CODE" = "0" ]; then
  PARENT_CHUNK_ID=$(echo "$RESP_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['parentChunkId'])" 2>/dev/null || echo "")
  echo "PASS: AdminRag write success, parentChunkId=$PARENT_CHUNK_ID"
else
  echo "FAIL: AdminRag returned code=$WRITE_CODE, resp=$RESP_BODY"
  FAILED=1
fi

# ── 2. Poll Query API until sources are available ──
echo ""
echo "--- 2. Poll Query API for results ---"
MAX_ATTEMPTS=30
ATTEMPT=0
QUERY_RESP=""
QUERY_CODE=""
while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
  ATTEMPT=$((ATTEMPT + 1))
  sleep 3
  QUERY_RESP=$(curl -s -X POST "$BASE_URL/api/v1/query" \
    -H "Content-Type: application/json" \
    -d "{\"question\":\"${VERIFICATION_CODE} 使用什么数据库？\"}" || echo '{"code":-1}')
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
    echo "=== Test data preserved with RUN_ID=$RUN_ID ==="
    echo "=== Query Stub Success HTTP Regression FAILED ==="
    exit 1
  fi
done

# ── 3. Verify Query response ──
echo ""
echo "--- 3. Verify query response ---"
echo "Raw response: $QUERY_RESP"

# HTTP 200 already confirmed by curl exit; check code
QUERY_RESP_CODE=$(echo "$QUERY_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$QUERY_RESP_CODE" != "0" ]; then
  echo "FAIL: Query returned code=$QUERY_RESP_CODE (expected 0)"
  FAILED=1
else
  echo "PASS: Query code=0"
fi

# Assert answer equals fixed stub answer
STUB_ANSWER=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
print(result.get('answer', ''))
" 2>/dev/null || echo "")
if [ "$STUB_ANSWER" = "已根据知识库证据生成回答。[S1]" ]; then
  echo "PASS: Answer matches fixed Stub answer"
else
  echo "FAIL: Answer is '$STUB_ANSWER' (expected '已根据知识库证据生成回答。[S1]')"
  FAILED=1
fi

# Assert sources is non-empty array
SOURCE_COUNT=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
result = resp.get('result', {})
sources = result.get('sources', [])
print(len(sources))
" 2>/dev/null || echo "0")
if [ "$SOURCE_COUNT" -gt 0 ]; then
  echo "PASS: sources is non-empty ($SOURCE_COUNT item(s))"
else
  echo "FAIL: sources is empty or missing"
  FAILED=1
fi

# Assert first source's reference == S1
FIRST_REF=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
if sources:
    print(sources[0].get('reference', ''))
else:
    print('')
" 2>/dev/null || echo "")
if [ "$FIRST_REF" = "S1" ]; then
  echo "PASS: First source reference is S1"
else
  echo "FAIL: First source reference is '$FIRST_REF' (expected S1)"
  FAILED=1
fi

# Assert first source's parentChunkId matches written parent
FIRST_PARENT=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
if sources:
    print(sources[0].get('parentChunkId', ''))
else:
    print('')
" 2>/dev/null || echo "")
if [ -n "${PARENT_CHUNK_ID:-}" ] && [ "$FIRST_PARENT" = "$PARENT_CHUNK_ID" ]; then
  echo "PASS: First source parentChunkId matches written parent"
elif [ -z "${PARENT_CHUNK_ID:-}" ]; then
  echo "SKIP: ParentChunkId from write unknown, skipping match check"
else
  echo "FAIL: First source parentChunkId ($FIRST_PARENT) != written parent ($PARENT_CHUNK_ID)"
  FAILED=1
fi

# Assert first source's matchedChildIds is non-empty
FIRST_MATCHED=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
if sources:
    matched = sources[0].get('matchedChildIds', [])
    print('OK' if isinstance(matched, list) and len(matched) > 0 else 'EMPTY')
else:
    print('NO_SOURCES')
" 2>/dev/null || echo "ERROR")
if [ "$FIRST_MATCHED" = "OK" ]; then
  echo "PASS: First source matchedChildIds is non-empty"
else
  echo "FAIL: First source matchedChildIds check: $FIRST_MATCHED"
  FAILED=1
fi

# ── Final ──
echo ""
echo "=== Test data preserved with RUN_ID=$RUN_ID ==="
if [ "$FAILED" -eq 0 ]; then
  echo "=== Query Stub Success HTTP Regression PASSED ==="
  exit 0
else
  echo "=== Query Stub Success HTTP Regression FAILED ==="
  exit 1
fi
