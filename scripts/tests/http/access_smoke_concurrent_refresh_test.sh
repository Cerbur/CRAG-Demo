#!/bin/bash
# Access smoke HTTP 回归 — 并发刷新仅一次成功，复用检测撤销 Family
set -euo pipefail
TIMEOUT=150
CONTAINER="crag-access-service-smoke"
SERVICE="access-service-smoke"
RUN_ID="$(date +%s)-$$"
USERNAME="cc_${RUN_ID}"
N=8

echo "=== Access Smoke Concurrent Refresh Test (run=$RUN_ID) ==="
docker compose up -d --build db redis access-service-smoke
echo "waiting for readiness..."
health="starting"
for _ in $(seq 1 "$TIMEOUT"); do
  health=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")
  [ "$health" = "healthy" ] && break
  sleep 2
done
[ "$health" = "healthy" ] || { echo "FAIL: 未就绪"; exit 1; }

reg=$(docker compose exec -T "$SERVICE" curl -s -X POST \
  "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" \
  -d "{\"nickname\":\"CC\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}")
token=$(echo "$reg" | sed 's/.*"refreshToken":"//; s/".*//')
[ -n "$token" ] || { echo "FAIL: 注册无 token"; exit 1; }

tmp=$(mktemp -d)
for i in $(seq 1 "$N"); do
  ( docker compose exec -T "$SERVICE" curl -s -o /dev/null -w "%{http_code}" -X POST \
      "http://localhost:8091/api/v1/smoke/access/refresh" \
      -H "Content-Type: application/json" -d "{\"refreshToken\":\"$token\"}" > "$tmp/$i.out" 2>/dev/null || echo "000" > "$tmp/$i.out" ) &
done
wait

ok=0; denied=0
for i in $(seq 1 "$N"); do
  code=$(cat "$tmp/$i.out" | tr -d '\r\n')
  case "$code" in
    200) ok=$((ok+1));;
    401) denied=$((denied+1));;
    *) echo "WARN: 意外状态 code=$code";;
  esac
done
rm -rf "$tmp"

[ "$ok" -eq 1 ] || { echo "FAIL: 并发刷新成功次数应为 1，实际 $ok"; exit 1; }
[ "$denied" -eq $((N-1)) ] || { echo "FAIL: 并发刷新拒绝次数应为 $((N-1))，实际 $denied"; exit 1; }

# Family 已撤销，任何后续刷新都 401
post=$(docker compose exec -T "$SERVICE" curl -s -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/refresh" \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$token\"}" || echo "000")
[ "$post" = "401" ] || { echo "FAIL: 并发后 Family 未撤销 (status=$post)"; exit 1; }

echo "PASS: 并发刷新仅一次成功 ($ok)，其余拒绝 ($denied)，Family 最终撤销"
echo "=== Access Smoke Concurrent Refresh Test PASSED ==="
