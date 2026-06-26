#!/bin/bash
# router2 RAG smoke HTTP 回归 — ingestion 状态事件发布（Plan 19）
# 摄取一份文档到 READY 后，断言 RAG outbox 出现 INGESTION_PROCESSING 与 INGESTION_READY 状态事件。
# 不清表、不删 volume；以 runId 隔离。
#
# 用法: bash scripts/tests/http/rag_smoke_ingestion_status_event_test.sh

set -euo pipefail

K_URL="${K_URL:-http://localhost:8094}"
R_URL="${R_URL:-http://localhost:8083}"
RUN_ID="r-evt-$(date +%s)-$$"
TIMEOUT=180

echo "=== router2 RAG Smoke Ingestion Status Event Test ==="
mkdir -p ./data/knowledge-files-smoke && chmod 777 ./data/knowledge-files-smoke
docker compose --profile smoke up -d --build db redis sidecar knowledge-service-smoke rag-service-smoke

wait_ready() {
  local url="$1" name="$2" status="000"
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health/readiness" || echo "000")
    [ "$status" = "200" ] && return 0; sleep 3
  done
  echo "FAIL: $name 未就绪"; return 1
}
wait_ready "$K_URL" knowledge || exit 1
wait_ready "$R_URL" rag || { docker compose logs --tail=40 rag-service-smoke || true; exit 1; }

TENANT=$(date +%s)
kbResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/knowledge-bases" -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT\",\"name\":\"kb-evt-$RUN_ID\",\"createdByUserId\":\"1\"}")
KB_ID=$(echo "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" || true)
[ -z "$KB_ID" ] && { echo "FAIL: 创建知识库失败"; exit 1; }

file=$(mktemp /tmp/rag-evt-XXXX.txt)
echo "status event content $RUN_ID alpha beta gamma 状态事件标记" > "$file"
SHA=$(sha256sum "$file" | awk '{print $1}'); SIZE=$(wc -c < "$file" | tr -d ' ')
upResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/documents/upload" \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$SHA" -F "sizeBytes=$SIZE" -F "file=@$file;filename=doc.txt")
rm -f "$file"
DOC_ID=$(echo "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" || true)
[ -z "$DOC_ID" ] && { echo "FAIL: 上传失败: $upResp"; exit 1; }

status=""
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=$KB_ID&docId=$DOC_ID" \
    | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
  [ "$status" = "READY" ] && break; [ "$status" = "FAILED" ] && { echo "FAIL: job FAILED"; exit 1; }; sleep 3
done
[ "$status" = "READY" ] || { echo "FAIL: job 未 READY"; exit 1; }

events=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/events?docId=$DOC_ID")
types=$(echo "$events" | python3 -c "import sys,json; print(' '.join(e['eventType'] for e in json.load(sys.stdin)['result']))" 2>/dev/null || echo "")
echo "status events: $types"
echo "$types" | grep -q INGESTION_PROCESSING || { echo "FAIL: 缺少 INGESTION_PROCESSING"; exit 1; }
echo "$types" | grep -q INGESTION_READY || { echo "FAIL: 缺少 INGESTION_READY"; exit 1; }

echo "PASS: INGESTION_PROCESSING 与 INGESTION_READY 状态事件已发布"
echo "=== router2 RAG Smoke Ingestion Status Event Test PASSED ==="
exit 0
