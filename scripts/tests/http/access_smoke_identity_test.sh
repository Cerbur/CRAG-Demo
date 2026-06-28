#!/bin/bash
# Access smoke HTTP 回归 — Identity 注册/登录/刷新/复用
set -euo pipefail
TIMEOUT=150
CONTAINER="crag-access-service"
SERVICE="access-service"
RUN_ID="$(date +%s)-$$"
USERNAME="iduser_${RUN_ID}"

echo "=== Access Smoke Identity Test (run=$RUN_ID) ==="
export CRAG_SERVICE_PROFILES=smoke
docker compose up -d --build db redis access-service
echo "waiting for readiness..."
health="starting"
for _ in $(seq 1 "$TIMEOUT"); do
  health=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")
  [ "$health" = "healthy" ] && break
  sleep 2
done
[ "$health" = "healthy" ] || { echo "FAIL: 未就绪"; docker compose logs --tail=60 "$SERVICE"; exit 1; }

curl_in() { docker compose exec -T "$SERVICE" curl -s "$@"; }

reg=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"Id User\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}")
echo "$reg" | grep -q '"accessToken"' || { echo "FAIL: register 无 token"; echo "$reg"; exit 1; }
refresh=$(echo "$reg" | sed 's/.*"refreshToken":"//; s/".*//')

login=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}")
echo "$login" | grep -q '"accessToken"' || { echo "FAIL: login 无 token"; exit 1; }

rot=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/refresh" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$refresh\"}")
echo "$rot" | grep -q '"accessToken"' || { echo "FAIL: refresh 无 token"; exit 1; }

# 旧 Token 复用应失败（401）
reuse_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/refresh" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$refresh\"}" || echo "000")
[ "$reuse_status" = "401" ] || { echo "FAIL: 旧 Token 复用未拒绝 (status=$reuse_status)"; exit 1; }

echo "PASS: 注册/登录/刷新成功，旧 Token 复用拒绝"
echo "=== Access Smoke Identity Test PASSED ==="
