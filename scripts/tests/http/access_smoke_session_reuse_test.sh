#!/bin/bash
# Access smoke HTTP 回归 — Refresh 轮换与复用检测
set -euo pipefail
TIMEOUT=150
CONTAINER="crag-access-service-smoke"
SERVICE="access-service-smoke"
RUN_ID="$(date +%s)-$$"
USERNAME="sr_${RUN_ID}"

echo "=== Access Smoke Session Reuse Test (run=$RUN_ID) ==="
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

reg=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"SR\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}")
first=$(json_field "$reg" refreshToken)

rot=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/refresh" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$first\"}")
new_token=$(json_field "$rot" refreshToken)
[ -n "$new_token" ] && [ "$new_token" != "$first" ] || { echo "FAIL: 轮换未产生新 token"; exit 1; }

old_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/refresh" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$first\"}" || echo "000")
[ "$old_status" = "401" ] || { echo "FAIL: 旧 Token 复用未拒绝 (status=$old_status)"; exit 1; }

new_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/refresh" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$new_token\"}" || echo "000")
[ "$new_status" = "401" ] || { echo "FAIL: 撤销 Family 后新 Token 仍可用 (status=$new_status)"; exit 1; }

echo "PASS: 轮换成功，旧 Token 复用撤销整个 Family"
echo "=== Access Smoke Session Reuse Test PASSED ==="
