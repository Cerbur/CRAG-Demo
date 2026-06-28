#!/bin/bash
# Access smoke HTTP 回归 — API Key 创建/鉴权/轮换/吊销/Scope 阻塞
set -euo pipefail
TIMEOUT=150
CONTAINER="crag-access-service"
SERVICE="access-service"
RUN_ID="$(date +%s)-$$"
OWNER="akowner_${RUN_ID}"
KB=$((2000000 + RUN_ID % 1000000))

echo "=== Access Smoke API Key Test (run=$RUN_ID) ==="
export CRAG_SERVICE_PROFILES=smoke
docker compose up -d --build db redis access-service
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

owner=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$OWNER\",\"password\":\"correct-horse-battery-12\"}")
owner_user=$(json_field "$owner" userId)
tenant=$(json_field "$owner" tenantId)

curl_in -X POST "http://localhost:8091/api/v1/smoke/access/scopes" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"knowledgeBaseId\":\"$KB\"}" >/dev/null

created=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/api-keys" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"knowledgeBaseId\":\"$KB\",\"name\":\"k1\",\"ttlSeconds\":2592000}")
key=$(json_field "$created" completeKey)
api_key_id=$(json_field "$created" apiKeyId)
echo "$key" | grep -q '^crag_' || { echo "FAIL: 完整 Key 格式错误"; exit 1; }

auth=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/api-keys/authenticate" \
  -H "Content-Type: application/json" -d "{\"apiKey\":\"$key\"}")
echo "$auth" | grep -q '"apiKeyId"' || { echo "FAIL: 鉴权失败"; echo "$auth"; exit 1; }

# 轮换：旧 Key 鉴权失败，新 Key 成功
rot=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/api-keys/$api_key_id/rotate" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"ttlSeconds\":2592000}")
new_key=$(json_field "$rot" completeKey)
old_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/api-keys/authenticate" \
  -H "Content-Type: application/json" -d "{\"apiKey\":\"$key\"}" || echo "000")
[ "$old_status" = "401" ] || { echo "FAIL: 轮换后旧 Key 仍可鉴权 (status=$old_status)"; exit 1; }
curl_in -X POST "http://localhost:8091/api/v1/smoke/access/api-keys/authenticate" \
  -H "Content-Type: application/json" -d "{\"apiKey\":\"$new_key\"}" | grep -q '"apiKeyId"' || { echo "FAIL: 新 Key 鉴权失败"; exit 1; }

# Scope 阻塞后 Key 鉴权失败
curl_in -X POST "http://localhost:8091/api/v1/smoke/access/scopes/$KB/block" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\"}" >/dev/null
block_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/api-keys/authenticate" \
  -H "Content-Type: application/json" -d "{\"apiKey\":\"$new_key\"}" || echo "000")
[ "$block_status" = "401" ] || { echo "FAIL: Scope 阻塞后 Key 仍可鉴权 (status=$block_status)"; exit 1; }

echo "PASS: API Key 创建/鉴权/轮换/Scope 阻塞正确"
echo "=== Access Smoke API Key Test PASSED ==="
