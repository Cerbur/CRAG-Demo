#!/bin/bash
# Access smoke HTTP 回归 — API Key 失效事件发布到 Redis Stream
set -euo pipefail
TIMEOUT=150
CONTAINER="crag-access-service-smoke"
SERVICE="access-service-smoke"
RUN_ID="$(date +%s)-$$"
OWNER="evowner_${RUN_ID}"
KB=$((3000000 + RUN_ID % 1000000))
STREAM="crag:event:access"

echo "=== Access Smoke Event Test (run=$RUN_ID) ==="
docker compose up -d --build db redis access-service-smoke
echo "waiting for readiness..."
health="starting"
for _ in $(seq 1 "$TIMEOUT"); do
  health=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")
  [ "$health" = "healthy" ] && break
  sleep 2
done
[ "$health" = "healthy" ] || { echo "FAIL: 未就绪"; exit 1; }

curl_in() { docker compose exec -T "$SERVICE" curl -s "$@"; }
json_field() { echo "$1" | sed "s/.*\"$2\":\"//; s/\".*//"; }
redis() { docker compose exec -T redis redis-cli "$@"; }

owner=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$OWNER\",\"password\":\"correct-horse-battery-12\"}")
owner_user=$(json_field "$owner" userId)
tenant=$(json_field "$owner" tenantId)

before=$(redis XLEN "$STREAM" 2>/dev/null | tr -d '\r\n' || echo 0)
curl_in -X POST "http://localhost:8091/api/v1/smoke/access/scopes" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"knowledgeBaseId\":\"$KB\"}" >/dev/null
created=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/api-keys" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"knowledgeBaseId\":\"$KB\",\"name\":\"k\",\"ttlSeconds\":2592000}")
api_key_id=$(json_field "$created" apiKeyId)
curl_in -X POST "http://localhost:8091/api/v1/smoke/access/api-keys/$api_key_id/revoke" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\"}" >/dev/null

# 等待 publisher 发布（poll 1s）
published=""
for _ in $(seq 1 20); do
  range=$(redis XRANGE "$STREAM" - + COUNT 50 2>/dev/null || true)
  if echo "$range" | grep -q "API_KEY_INVALIDATED"; then
    published="yes"
    break
  fi
  sleep 1
done
[ -n "$published" ] || { echo "FAIL: 未在 $STREAM 发现 API_KEY_INVALIDATED 事件"; exit 1; }

echo "PASS: API Key 失效事件已发布到 Redis Stream $STREAM"
echo "=== Access Smoke Event Test PASSED ==="
