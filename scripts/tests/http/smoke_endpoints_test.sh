#!/bin/bash
# CRAG-Demo Smoke HTTP Regression — smoke Profile 模式
# 验证显式 smoke Profile 启动后诊断端点可用。
#
# 用法: bash scripts/tests/http/smoke_endpoints_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8083（rag-service-smoke 服务端口）

set -euo pipefail

BASE_URL="${1:-http://localhost:8083}"
RUN_ID="smoke-$(date +%Y%m%d-%H%M%S)-$$"
FAILED=0

echo "=== Smoke Endpoints Test ==="
echo "BASE_URL=$BASE_URL"
echo "RUN_ID=$RUN_ID"

# 1. /smoke — 基础连通性
echo "--- /smoke ---"
resp=$(curl -s "$BASE_URL/api/v1/test/smoke" || echo '{"code":-1}')
code=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$code" = "0" ]; then
  echo "PASS: /smoke code=$code"
else
  echo "FAIL: /smoke unexpected response: $resp"
  FAILED=1
fi

# 2. /chunk — 写入
echo "--- /chunk ---"
resp=$(curl -s -X POST "$BASE_URL/api/v1/test/chunk" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"smoke test $RUN_ID\",\"content\":\"smoke test content for verification $RUN_ID\"}" || echo '{"code":-1}')
code=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$code" = "0" ]; then
  echo "PASS: /chunk code=$code"
else
  echo "FAIL: /chunk unexpected response: $resp"
  FAILED=1
fi

# 3. /retrieval — 检索全链路
echo "--- /retrieval ---"
resp=$(curl -s "$BASE_URL/api/v1/test/retrieval?query=test&topN=3" || echo '{"code":-1}')
code=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code','-1'))" 2>/dev/null || echo "-1")
if [ "$code" = "0" ]; then
  echo "PASS: /retrieval code=$code"
else
  echo "FAIL: /retrieval unexpected response: $resp"
  FAILED=1
fi

if [ "$FAILED" -eq 0 ]; then
  echo "=== Smoke endpoints test PASSED ==="
  exit 0
else
  echo "=== Smoke endpoints test FAILED ==="
  exit 1
fi
