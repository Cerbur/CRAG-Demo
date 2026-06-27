#!/bin/bash
# Access smoke HTTP 回归 — Membership 添加/角色/移除/最后 OWNER
set -euo pipefail
TIMEOUT=150
CONTAINER="crag-access-service-smoke"
SERVICE="access-service-smoke"
RUN_ID="$(date +%s)-$$"
OWNER="mowner_${RUN_ID}"
MEMBER="mmember_${RUN_ID}"

echo "=== Access Smoke Membership Test (run=$RUN_ID) ==="
docker compose up -d --build db redis access-service-smoke
echo "waiting for readiness..."
health="starting"
for _ in $(seq 1 "$TIMEOUT"); do
  health=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")
  [ "$health" = "healthy" ] && break
  sleep 2
done
[ "$health" = "healthy" ] || { echo "FAIL: 未就绪"; docker compose logs --tail=60 "$SERVICE"; exit 1; }

curl_in() { docker compose exec -T "$SERVICE" curl -s "$@"; }
json_field() { echo "$1" | sed "s/.*\"$2\":\"//; s/\".*//"; }

owner=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$OWNER\",\"password\":\"correct-horse-battery-12\"}")
owner_user=$(json_field "$owner" userId)
tenant=$(json_field "$owner" tenantId)

member=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"Member\",\"username\":\"$MEMBER\",\"password\":\"correct-horse-battery-12\"}")
member_user=$(json_field "$member" userId)

# 添加成员
added=$(curl_in -X POST "http://localhost:8091/api/v1/smoke/access/memberships/add" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"username\":\"$MEMBER\"}")
echo "$added" | grep -q '"role":"MEMBER"' || { echo "FAIL: 添加成员失败"; echo "$added"; exit 1; }

# 唯一 OWNER 降级自己应失败（409）
down_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/memberships/$owner_user/role" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"role\":\"MEMBER\"}" || echo "000")
[ "$down_status" = "409" ] || { echo "FAIL: 最后 OWNER 降级未拒绝 (status=$down_status)"; exit 1; }

# 升级 member 为 OWNER（现在两名 OWNER）
curl_in -X POST "http://localhost:8091/api/v1/smoke/access/memberships/$member_user/role" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\",\"role\":\"OWNER\"}" >/dev/null

# 移除原 OWNER 应成功
rm_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/memberships/$owner_user/remove" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$owner_user\",\"tenantId\":\"$tenant\"}" || echo "000")
[ "$rm_status" = "200" ] || { echo "FAIL: 多 OWNER 时移除失败 (status=$rm_status)"; exit 1; }

# 移除最后一名 OWNER 应失败（409）
last_status=$(curl_in -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/memberships/$member_user/remove" \
  -H "Content-Type: application/json" \
  -d "{\"actorUserId\":\"$member_user\",\"tenantId\":\"$tenant\"}" || echo "000")
[ "$last_status" = "409" ] || { echo "FAIL: 最后 OWNER 移除未拒绝 (status=$last_status)"; exit 1; }

echo "PASS: 成员添加/角色/最后 OWNER 保护正确"
echo "=== Access Smoke Membership Test PASSED ==="
