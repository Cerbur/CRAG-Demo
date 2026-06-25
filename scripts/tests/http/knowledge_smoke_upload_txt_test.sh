#!/bin/bash
# Knowledge smoke HTTP 回归 — 上传 .txt 成功
# 在 smoke profile 下创建知识库、上传 .txt、断言 Document PENDING 并读回原始内容。
# 自动启动 db/redis/knowledge-service-smoke，以 runId 隔离数据；不清表、不删 volume。
#
# 用法: bash scripts/tests/http/knowledge_smoke_upload_txt_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8094

set -euo pipefail

BASE_URL="${1:-http://localhost:8094}"
RUN_ID="k-txt-$(date +%s)-$$"
TIMEOUT=120

echo "=== Knowledge Smoke Upload TXT Test ==="
echo "BASE_URL=$BASE_URL  RUN_ID=$RUN_ID"

mkdir -p ./data/knowledge-files-smoke && chmod 777 ./data/knowledge-files-smoke
docker compose --profile smoke up -d --build db redis knowledge-service-smoke

cleanup() {
  rm -f "$CONTENT_FILE"
  docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
}

echo "waiting for knowledge-service-smoke readiness..."
status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
if [ "$status" != "200" ]; then
  echo "FAIL: knowledge-service-smoke 未就绪 (status=$status)"
  docker compose logs --tail=60 knowledge-service-smoke || true
  cleanup
  exit 1
fi

TENANT=$(date +%s)
CONTENT_FILE=$(mktemp /tmp/knowledge-txt-XXXX.txt)
echo "hello knowledge smoke txt $RUN_ID" > "$CONTENT_FILE"
SHA=$(sha256sum "$CONTENT_FILE" | awk '{print $1}')
SIZE=$(wc -c < "$CONTENT_FILE" | tr -d ' ')

kbResp=$(curl -s -X POST "$BASE_URL/api/v1/smoke/knowledge/knowledge-bases" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT\",\"name\":\"kb-$RUN_ID\",\"createdByUserId\":\"1\"}")
KB_ID=$(echo "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" 2>/dev/null || echo "")
if [ -z "$KB_ID" ]; then
  echo "FAIL: 创建知识库失败: $kbResp"
  cleanup
  exit 1
fi

upResp=$(curl -s -X POST "$BASE_URL/api/v1/smoke/knowledge/documents/upload" \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$SHA" -F "sizeBytes=$SIZE" -F "file=@$CONTENT_FILE;filename=doc.txt")
DOC_ID=$(echo "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" 2>/dev/null || echo "")
INGEST=$(echo "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['ingestionStatus'])" 2>/dev/null || echo "")
if [ -z "$DOC_ID" ] || [ "$INGEST" != "PENDING" ]; then
  echo "FAIL: 上传失败: $upResp"
  cleanup
  exit 1
fi

readBody=$(curl -s "$BASE_URL/api/v1/smoke/knowledge/documents/$DOC_ID/file?tenantId=$TENANT")
expected=$(cat "$CONTENT_FILE")
if [ "$readBody" != "$expected" ]; then
  echo "FAIL: 读回内容不匹配"
  cleanup
  exit 1
fi

echo "PASS: uploaded doc=$DOC_ID status=$INGEST, read-back OK"
echo "=== Knowledge Smoke Upload TXT Test PASSED ==="
cleanup
exit 0
