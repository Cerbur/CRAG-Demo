#!/bin/bash
# Knowledge smoke HTTP 回归 — DOC_UPLOADED 发布到 Redis Streams
# 在 smoke profile 下上传 .txt，轮询事件诊断端点直到 outbox 状态为 PUBLISHED，
# 证明真实 PostgreSQL + 文件 volume + Redis Streams 发布链路。
# 自动启动 db/redis/knowledge-service-smoke，以 runId 隔离数据；不清表、不删 volume。
#
# 用法: bash scripts/tests/http/knowledge_smoke_event_published_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8094

set -euo pipefail

BASE_URL="${1:-http://localhost:8094}"
RUN_ID="k-evt-$(date +%s)-$$"
TIMEOUT=120

echo "=== Knowledge Smoke Event Published Test ==="
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
CONTENT_FILE=$(mktemp /tmp/knowledge-evt-XXXX.txt)
echo "doc uploaded for event $RUN_ID" > "$CONTENT_FILE"
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
if [ -z "$DOC_ID" ]; then
  echo "FAIL: 上传失败: $upResp"
  cleanup
  exit 1
fi
echo "uploaded doc=$DOC_ID, polling DOC_UPLOADED publish status..."

outbox=""
for _ in $(seq 1 "$TIMEOUT"); do
  resp=$(curl -s "$BASE_URL/api/v1/smoke/knowledge/documents/$DOC_ID/event")
  outbox=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['outboxStatus'])" 2>/dev/null || echo "")
  if [ "$outbox" = "PUBLISHED" ]; then
    echo "PASS: DOC_UPLOADED PUBLISHED (doc=$DOC_ID)"
    echo "=== Knowledge Smoke Event Published Test PASSED ==="
    cleanup
    exit 0
  fi
  sleep 2
done

echo "FAIL: 超时，DOC_UPLOADED 未发布。最后状态: outbox=$outbox"
echo "--- diagnostics ---"; echo "$resp"
docker compose logs --tail=40 knowledge-service-smoke || true
cleanup
exit 1
