#!/bin/bash
# CRAG-Demo Event Smoke HTTP Regression — 成功事件闭环
# 在 smoke profile 下验证 Knowledge 事件 publish → consume → PROCESSED 的成功路径。
# 自动启动 db/redis/knowledge-service-smoke（按需），以 runId 隔离数据；不清表、不清 Redis。
#
# 用法: bash scripts/tests/http/event_smoke_success_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8094（knowledge-service-smoke 宿主机端口）

set -euo pipefail

BASE_URL="${1:-http://localhost:8094}"
RUN_ID="evt-success-$(date +%s)-$$"
TIMEOUT=120
FAILED=0

echo "=== Event Smoke Success Test ==="
echo "BASE_URL=$BASE_URL  RUN_ID=$RUN_ID"

# 1. 启动所需服务（已运行则跳过）
docker compose --profile smoke up -d --build db redis knowledge-service-smoke

# 2. 等待 readiness
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
  exit 1
fi

# 3. 创建 smoke 事件
resp=$(curl -s -X POST "$BASE_URL/api/v1/smoke/events" -H "Content-Type: application/json" \
  -d "{\"runId\":\"$RUN_ID\",\"message\":\"smoke success\",\"failMode\":\"NONE\"}")
eventId=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['eventId'])" 2>/dev/null || echo "")
outbox=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['outboxStatus'])" 2>/dev/null || echo "")
if [ -z "$eventId" ] || [ "$outbox" != "PENDING" ]; then
  echo "FAIL: 创建事件失败: $resp"
  docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
  exit 1
fi
echo "created eventId=$eventId (outbox=PENDING)"

# 4. 轮询直到 PUBLISHED + PROCESSED + 非 DLQ
outbox=""; processed=""
for _ in $(seq 1 "$TIMEOUT"); do
  resp=$(curl -s "$BASE_URL/api/v1/smoke/events/$eventId")
  outbox=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['outboxStatus'])" 2>/dev/null || echo "")
  processed=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result'].get('processedStatus') or '')" 2>/dev/null || echo "")
  dlq=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result'].get('deadLettered'))" 2>/dev/null || echo "")
  if [ "$outbox" = "PUBLISHED" ] && [ "$processed" = "PROCESSED" ] && [ "$dlq" = "False" ]; then
    echo "PASS: outbox=$outbox processed=$processed deadLettered=$dlq"
    echo "=== Event Smoke Success Test PASSED ==="
    docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
    exit 0
  fi
  sleep 2
done

echo "FAIL: 超时。最后状态: outbox=$outbox processed=$processed"
echo "--- diagnostics ---"; echo "$resp"
docker compose logs --tail=40 knowledge-service-smoke || true
docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
exit 1
