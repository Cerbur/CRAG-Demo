#!/bin/bash
# router2 RAG smoke HTTP 回归 — DOC_UPLOADED 非 retryable 失败进 DLQ（Plan 19）
# 向 crag:event:knowledge 注入一条 envelope 合法但 payload 非法的 DOC_UPLOADED 事件，
# 断言 RAG consumer 将其映射为 nonRetryableFailure（ACK 到 DLQ）且不创建 ingestion_job。
# 不清表、不删 volume；以 runId 隔离。
#
# 用法: bash scripts/tests/http/rag_smoke_doc_uploaded_dlq_test.sh

set -euo pipefail

R_URL="${R_URL:-http://localhost:8083}"
RUN_ID="r-dlq-$(date +%s)-$$"
TIMEOUT=180
STREAM="crag:event:knowledge"
DLQ="$STREAM:dlq"
BAD_DOC=$(( (RANDOM*RANDOM) % 1000000 + 900000 ))

echo "=== router2 RAG Smoke DOC_UPLOADED DLQ Test ==="
mkdir -p ./data/knowledge-files-smoke && chmod 777 ./data/knowledge-files-smoke
docker compose --profile smoke up -d --build db redis sidecar rag-service-smoke

wait_ready() {
  local url="$1" name="$2" status="000"
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health/readiness" || echo "000")
    [ "$status" = "200" ] && return 0; sleep 3
  done
  echo "FAIL: $name 未就绪"; return 1
}
wait_ready "$R_URL" rag || { docker compose logs --tail=40 rag-service-smoke || true; exit 1; }

# 注入 envelope 合法、payload 非法的 DOC_UPLOADED（缺字段 + 非法类型）
docker exec crag-redis redis-cli XADD "$STREAM" '*' \
  eventId "$(( (RANDOM*RANDOM) % 1000000 + 1 ))" \
  eventType DOC_UPLOADED producer knowledge-service resourceType DOCUMENT \
  resourceId "$BAD_DOC" operationVersion 1 \
  occurredAt "2026-06-27T00:00:00Z" traceId "dlq-$RUN_ID" payloadVersion 1 \
  payload '{"tenantId":"not-a-number"}' >/dev/null

# 等待 RAG 消费处理
sleep 12

# 断言：非法 payload 不创建 ingestion_job（job 查询返回 NONE/null）
job=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=1&docId=$BAD_DOC" \
  | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
[ "$job" = "NONE" ] || { echo "FAIL: 非法 payload 不应创建 Job，得到 status=$job"; exit 1; }

# 断言：DLQ stream 有新条目（dead-letter）
dlq_len=$(docker exec crag-redis redis-cli XLEN "$DLQ" 2>/dev/null | tr -d ' \n' || echo "0")
echo "DLQ length=$dlq_len"
[ "$dlq_len" -ge 1 ] || { echo "FAIL: 非法事件未进入 DLQ"; exit 1; }

echo "PASS: 非法 DOC_UPLOADED 进入 DLQ 且未创建 Job"
echo "=== router2 RAG Smoke DOC_UPLOADED DLQ Test PASSED ==="
exit 0
