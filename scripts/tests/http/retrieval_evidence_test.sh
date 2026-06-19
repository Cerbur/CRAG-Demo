#!/bin/bash
# CRAG-Demo Parent Evidence HTTP Regression
# 验证 retrieveEvidence 在真实 PostgreSQL 链路返回完整 parent 内容与真实命中 child.
#
# 用法: bash scripts/tests/http/retrieval_evidence_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8081（app-smoke 服务端口）
#
# 测试数据使用唯一 RUN_ID 隔离，不执行破坏性清理.

set -euo pipefail

BASE_URL="${1:-http://localhost:8081}"
RUN_ID="evidence-$(date +%s)-$$"
FAILED=0

echo "=== Parent Evidence HTTP Regression ==="
echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"

# ── 1. Write a parent+child document ──
echo "--- 1. Write test document ---"
WRITE_RESP=$(curl -s -X POST "$BASE_URL/api/v1/test/chunk" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"evidence-test-$RUN_ID\",\"content\":\"$RUN_ID parent evidence test content for verification purposes\"}" || echo '{"code":-1}')
WRITE_CODE=$(echo "$WRITE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$WRITE_CODE" = "0" ]; then
  PARENT_ID=$(echo "$WRITE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['parent_chunk_ids'][0])" 2>/dev/null || echo "")
  echo "PASS: /chunk write success, parent_id=$PARENT_ID"
else
  echo "FAIL: /chunk returned code=$WRITE_CODE, resp=$WRITE_RESP"
  FAILED=1
fi

# ── 2. Poll for dense indexing completion ──
echo "--- 2. Wait for dense indexing ---"
MAX_WAIT=120
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
  sleep 5
  WAITED=$((WAITED + 5))
  # Use retrieval as a proxy for indexing readiness
  RET_RESP=$(curl -s "$BASE_URL/api/v1/test/retrieval?query=$RUN_ID&topN=1" || echo '{"code":-1}')
  RET_CODE=$(echo "$RET_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
  if [ "$RET_CODE" = "0" ]; then
    RESULT_COUNT=$(echo "$RET_RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']['results']))" 2>/dev/null || echo "0")
    if [ "$RESULT_COUNT" -gt 0 ]; then
      echo "Indexing ready after ${WAITED}s (found $RESULT_COUNT result(s))"
      break
    fi
  fi
  echo "  Waiting... ${WAITED}s / ${MAX_WAIT}s"
  if [ $WAITED -ge $MAX_WAIT ]; then
    echo "WARN: Indexing may not be complete after ${MAX_WAIT}s — proceeding with evidence test anyway"
  fi
done

# ── 3. Call /retrieval/evidence ──
echo "--- 3. Parent evidence retrieval ---"
EVIDENCE_RESP=$(curl -s "$BASE_URL/api/v1/test/retrieval/evidence?query=$RUN_ID&topN=3" || echo '{"code":-1}')
EVIDENCE_CODE=$(echo "$EVIDENCE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$EVIDENCE_CODE" != "0" ]; then
  echo "FAIL: /retrieval/evidence returned code=$EVIDENCE_CODE, resp=$EVIDENCE_RESP"
  FAILED=1
else
  echo "PASS: /retrieval/evidence HTTP 200"
fi

# ── 4. Verify response structure ──
echo "--- 4. Verify evidence response structure ---"
RESULT_COUNT=$(echo "$EVIDENCE_RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['result']))" 2>/dev/null || echo "0")
echo "Evidence result count: $RESULT_COUNT"

if [ "$RESULT_COUNT" -eq 0 ]; then
  echo "FAIL: No evidence results returned — indexing may not be complete, or query may not match"
  FAILED=1
else
  # Check each evidence result has required fields
  FIRST_PARENT=$(echo "$EVIDENCE_RESP" | python3 -c "
import sys, json
results = json.load(sys.stdin)['result']
first = results[0]
print(first.get('parentChunkId', 'MISSING'))
" 2>/dev/null || echo "ERROR")
  echo "First parent chunk ID: $FIRST_PARENT"

  FIRST_CONTENT=$(echo "$EVIDENCE_RESP" | python3 -c "
import sys, json
results = json.load(sys.stdin)['result']
first = results[0]
content = first.get('content', 'MISSING')
print('OK' if content and len(content) > 0 else 'EMPTY')
" 2>/dev/null || echo "ERROR")
  echo "First parent content check: $FIRST_CONTENT"

  FIRST_MATCHED=$(echo "$EVIDENCE_RESP" | python3 -c "
import sys, json
results = json.load(sys.stdin)['result']
first = results[0]
matched = first.get('matchedChildIds', [])
print('OK' if isinstance(matched, list) and len(matched) > 0 else 'EMPTY_LIST')
" 2>/dev/null || echo "ERROR")
  echo "First parent matchedChildIds check: $FIRST_MATCHED"

  if [ "$FIRST_PARENT" != "MISSING" ] && [ "$FIRST_CONTENT" = "OK" ] && [ "$FIRST_MATCHED" = "OK" ]; then
    echo "PASS: Evidence response structure valid"
  else
    echo "FAIL: Evidence response missing required fields"
    FAILED=1
  fi

  # ── 4a. Assert first result parentChunkId matches written parent ──
  if [ -n "${PARENT_ID:-}" ] && [ "$FIRST_PARENT" != "$PARENT_ID" ]; then
    echo "FAIL: First parentChunkId ($FIRST_PARENT) != written parent ($PARENT_ID)"
    FAILED=1
  elif [ -n "${PARENT_ID:-}" ]; then
    echo "PASS: First parentChunkId matches written parent ID"
  fi

  # ── 4b. Assert first result content contains unique runId ──
  CONTENT_HAS_RUNID=$(echo "$EVIDENCE_RESP" | python3 -c "
import sys, json
results = json.load(sys.stdin)['result']
first = results[0]
content = first.get('content', '')
run_id = '${RUN_ID}'
print('OK' if run_id in content else 'MISSING')
" 2>/dev/null || echo "ERROR")
  if [ "$CONTENT_HAS_RUNID" = "OK" ]; then
    echo "PASS: First evidence content contains RUN_ID"
  else
    echo "FAIL: First evidence content does not contain RUN_ID=$RUN_ID"
    FAILED=1
  fi
fi

# ── 5. Verify stable ordering (same request twice) ──
echo "--- 5. Verify stable ordering ---"
EVIDENCE_RESP2=$(curl -s "$BASE_URL/api/v1/test/retrieval/evidence?query=$RUN_ID&topN=3" || echo '{"code":-1}')
ORDER1=$(echo "$EVIDENCE_RESP" | python3 -c "import sys,json; print([r['parentChunkId'] for r in json.load(sys.stdin).get('result',[])])" 2>/dev/null || echo "[]")
ORDER2=$(echo "$EVIDENCE_RESP2" | python3 -c "import sys,json; print([r['parentChunkId'] for r in json.load(sys.stdin).get('result',[])])" 2>/dev/null || echo "[]")
if [ "$ORDER1" = "$ORDER2" ]; then
  echo "PASS: Stable ordering confirmed — $ORDER1"
else
  echo "FAIL: Ordering differs between calls: $ORDER1 vs $ORDER2"
  FAILED=1
fi

# ── 6. Verify matched child IDs contain real RRF hit children ──
echo "--- 6. Verify matched child IDs are non-empty for results ---"
ALL_HAVE_MATCHED=$(echo "$EVIDENCE_RESP" | python3 -c "
import sys, json
results = json.load(sys.stdin).get('result', [])
all_ok = all(len(r.get('matchedChildIds', [])) > 0 for r in results)
print('OK' if all_ok else 'FAIL')
" 2>/dev/null || echo "FAIL")
if [ "$ALL_HAVE_MATCHED" = "OK" ] || [ "$RESULT_COUNT" = "0" ]; then
  echo "PASS: All evidence results have non-empty matchedChildIds"
else
  echo "FAIL: Some evidence results have empty matchedChildIds"
  FAILED=1
fi

# ── Final ──
echo ""
echo "=== Test data preserved with RUN_ID=$RUN_ID ==="
if [ "$FAILED" -eq 0 ]; then
  echo "=== Parent Evidence HTTP Regression PASSED ==="
  exit 0
else
  echo "=== Parent Evidence HTTP Regression FAILED ==="
  exit 1
fi
