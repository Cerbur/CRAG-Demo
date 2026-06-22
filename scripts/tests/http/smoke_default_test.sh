#!/bin/bash
# CRAG-Demo Smoke HTTP Regression — 默认模式
# 验证默认启动（无 smoke Profile）下 /api/v1/test/** 不可访问。
#
# 用法: bash scripts/tests/http/smoke_default_test.sh [BASE_URL]
#       BASE_URL 默认 CRAG_RAG_BASE_URL 环境变量或 http://localhost:8082

set -euo pipefail

BASE_URL="${1:-${CRAG_RAG_BASE_URL:-http://localhost:8082}}"
FAILED=0

echo "=== Smoke Default Test ==="
echo "BASE_URL=$BASE_URL"

# 以下端点默认模式下应返回 404（不可访问）
ENDPOINTS=(
  "/api/v1/test/smoke"
  "/api/v1/test/retrieval?query=test&topN=3"
  "/api/v1/test/rrf?query=test&topN=3"
  "/api/v1/test/rerank?query=test&topN=3"
)

for ep in "${ENDPOINTS[@]}"; do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL$ep" || echo "FAIL")
  if [ "$status" = "404" ]; then
    echo "PASS: $ep → $status"
  elif [ "$status" = "000" ]; then
    echo "FAIL: $ep → connection failed (HTTP 000)"
    FAILED=1
  else
    echo "FAIL: $ep → expected 404, got $status"
    FAILED=1
  fi
done

if [ "$FAILED" -eq 0 ]; then
  echo "=== Default smoke test PASSED ==="
  exit 0
else
  echo "=== Default smoke test FAILED ==="
  exit 1
fi
