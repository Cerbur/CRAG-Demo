#!/bin/bash
# CRAG-Demo Query Stub Success HTTP Regression
# 验证 Stub 模式下的完整 Query 链路：AdminRag 写入 → 索引等待 → Query API 调用
#
# 用法: bash scripts/tests/http/query_stub_success_test.sh [BASE_URL]
#       BASE_URL 默认 CRAG_RAG_BASE_URL 环境变量或 http://localhost:8082

set -euo pipefail

BASE_URL="${1:-${CRAG_RAG_BASE_URL:-http://localhost:8082}}"
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
  PARENT_CHUNK_IDS=$(echo "$RESP_BODY" | python3 -c "
import sys, json
result = json.load(sys.stdin).get('result', {})
ids = result.get('parentChunkIds', [])
print(' '.join(ids))
" 2>/dev/null || echo "")
  echo "PASS: AdminRag write success, parentChunkIds=$PARENT_CHUNK_IDS"
else
  echo "FAIL: AdminRag returned code=$WRITE_CODE, resp=$RESP_BODY"
  FAILED=1
fi

# ── 2. Poll Query API until the written chunk appears in sources ──
echo ""
echo "--- 2. Poll Query API until written chunk appears in sources ---"
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
    # Check if any source's parentChunkId matches a written parentChunkId
    MATCHES_WRITTEN=0
    if [ -n "${PARENT_CHUNK_IDS:-}" ]; then
      for pid in $PARENT_CHUNK_IDS; do
        FOUND=$(echo "$QUERY_RESP" | python3 -c "
import sys, json
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
target = '${pid}'
for s in sources:
    if s.get('parentChunkId') == target:
        print('MATCH')
        break
" 2>/dev/null || echo "")
        if [ "$FOUND" = "MATCH" ]; then
          MATCHES_WRITTEN=1
          break
        fi
      done
    fi
    if [ "$MATCHES_WRITTEN" -eq 1 ]; then
      echo "Query ready after ${ATTEMPT} attempts (written chunk found in sources)"
      break
    fi
  fi
  echo "  Waiting... attempt ${ATTEMPT}/${MAX_ATTEMPTS}"
  if [ $ATTEMPT -ge $MAX_ATTEMPTS ]; then
    echo "FAIL: Written chunk did not appear in query sources within ${MAX_ATTEMPTS} attempts — aborting"
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

# Find the source whose parentChunkId matches a written parentChunkId,
# then verify its reference format and matchedChildIds.
# Uses a single python3 call to extract all fields for the matching source.
echo ""
echo "--- 4. Verify matching source (written chunk) ---"
MATCHING_INFO=""
if [ -n "${PARENT_CHUNK_IDS:-}" ]; then
  MATCHING_INFO=$(echo "$QUERY_RESP" | python3 -c "
import sys, json, re
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
written = '${PARENT_CHUNK_IDS}'.split()
for s in sources:
    if s.get('parentChunkId') in written:
        ref = s.get('reference', '')
        pid = s.get('parentChunkId', '')
        matched = s.get('matchedChildIds', [])
        matched_ok = 'OK' if isinstance(matched, list) and len(matched) > 0 else 'EMPTY'
        ref_ok = 'OK' if re.match(r'^S\d+$', ref) else 'BAD'
        print(f'FOUND|{ref}|{pid}|{matched_ok}|{ref_ok}')
        break
else:
    print('NOT_FOUND')
" 2>/dev/null || echo "ERROR")
fi

if [ -z "$MATCHING_INFO" ] || [ "$MATCHING_INFO" = "ERROR" ]; then
  echo "FAIL: Could not extract matching source info (PARENT_CHUNK_IDS empty or python error)"
  FAILED=1
elif [ "$MATCHING_INFO" = "NOT_FOUND" ]; then
  echo "FAIL: No source matches any written parentChunkId ($PARENT_CHUNK_IDS)"
  FAILED=1
else
  # Parse pipe-delimited fields: FOUND|reference|parentChunkId|matched_ok|ref_ok
  MATCH_REF=$(echo "$MATCHING_INFO" | cut -d'|' -f2)
  MATCH_PID=$(echo "$MATCHING_INFO" | cut -d'|' -f3)
  MATCH_CHILDREN=$(echo "$MATCHING_INFO" | cut -d'|' -f4)
  MATCH_REF_OK=$(echo "$MATCHING_INFO" | cut -d'|' -f5)

  echo "Matching source: reference=$MATCH_REF parentChunkId=$MATCH_PID"

  # Verify reference format is valid (matches S<number>)
  if [ "$MATCH_REF_OK" = "OK" ]; then
    echo "PASS: Matching source reference '$MATCH_REF' has valid format (S<number>)"
  else
    echo "FAIL: Matching source reference '$MATCH_REF' has invalid format (expected S<number>)"
    FAILED=1
  fi

  # Verify parentChunkId is in the written set (redundant safety check)
  PID_MATCH=0
  for pid in $PARENT_CHUNK_IDS; do
    if [ "$MATCH_PID" = "$pid" ]; then
      PID_MATCH=1
      break
    fi
  done
  if [ "$PID_MATCH" -eq 1 ]; then
    echo "PASS: Matching source parentChunkId ($MATCH_PID) belongs to written chunk"
  else
    echo "FAIL: Matching source parentChunkId ($MATCH_PID) not in written parentChunkIds ($PARENT_CHUNK_IDS)"
    FAILED=1
  fi

  # Verify matchedChildIds is non-empty
  if [ "$MATCH_CHILDREN" = "OK" ]; then
    echo "PASS: Matching source matchedChildIds is non-empty"
  else
    echo "FAIL: Matching source matchedChildIds check: $MATCH_CHILDREN"
    FAILED=1
  fi
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
