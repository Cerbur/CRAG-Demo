#!/bin/bash
# CRAG-Demo Event Smoke HTTP Regression — DLQ 路径
# 在 smoke profile 下验证 retryable failure（failMode=always）经 reclaim 重试后进入 DLQ，
# processed_event 标记 DEAD_LETTERED，deadLettered=true，handler 尝试次数达到上限。
# 自动启动 db/redis/knowledge-service-smoke（按需），以 runId 隔离数据。
#
# 用法: bash scripts/tests/http/event_smoke_dlq_test.sh [BASE_URL]

set -euo pipefail

BASE_URL="${1:-http://localhost:8094}"
MAX_DELIVERIES="${CARG_EVENT_MAX_DELIVERIES:-3}"
RUN_ID="evt-dlq-$(date +%s)-$$"
TIMEOUT=180

echo "=== Event Smoke DLQ Test ==="
echo "BASE_URL=$BASE_URL  RUN_ID=$RUN_ID  maxDeliveries=$MAX_DELIVERIES"

docker compose --profile smoke up -d --build db redis knowledge-service-smoke

echo "waiting for knowledge-service-smoke readiness..."
status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: 未就绪"; docker compose logs --tail=60 knowledge-service-smoke || true; exit 1; }

resp=$(curl -s -X POST "$BASE_URL/api/v1/smoke/events" -H "Content-Type: application/json" \
  -d "{\"runId\":\"$RUN_ID\",\"message\":\"smoke dlq\",\"failMode\":\"ALWAYS\"}")
eventId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['eventId'])" 2>/dev/null || echo "")
if [ -z "$eventId" ]; then
  echo "FAIL: 创建事件失败: $resp"
  docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
  exit 1
fi
echo "created eventId=$eventId (failMode=ALWAYS)"

processed=""; attempts="0"
for _ in $(seq 1 "$TIMEOUT"); do
  resp=$(curl -s "$BASE_URL/api/v1/smoke/events/$eventId")
  processed=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result'].get('processedStatus') or '')" 2>/dev/null || echo "")
  attempts=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result'].get('handlerAttemptCount') or 0)" 2>/dev/null || echo "0")
  dlq=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result'].get('deadLettered'))" 2>/dev/null || echo "")
  if [ "$processed" = "DEAD_LETTERED" ] && [ "$dlq" = "True" ] && [ "$attempts" -ge "$MAX_DELIVERIES" ]; then
    echo "PASS: processed=$processed deadLettered=$dlq handlerAttempts=$attempts"
    echo "=== Event Smoke DLQ Test PASSED ==="
    docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
    exit 0
  fi
  sleep 3
done

echo "FAIL: 超时。最后状态: processed=$processed handlerAttempts=$attempts"
echo "--- diagnostics ---"; echo "$resp"
docker compose logs --tail=40 knowledge-service-smoke || true
docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
exit 1
