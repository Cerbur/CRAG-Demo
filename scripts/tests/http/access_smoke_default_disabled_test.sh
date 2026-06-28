#!/bin/bash
# Access smoke HTTP 回归 — 默认 Profile 禁用
# 验证默认 access-service（无 smoke Profile）不暴露 /api/v1/smoke/access/**。
set -euo pipefail

TIMEOUT=150
CONTAINER="crag-access-service"
SERVICE="access-service"

echo "=== Access Smoke Default Disabled Test ==="
export CRAG_SERVICE_PROFILES=
docker compose up -d --build db redis access-service

echo "waiting for access-service readiness..."
health="starting"
for _ in $(seq 1 "$TIMEOUT"); do
  health=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")
  [ "$health" = "healthy" ] && break
  sleep 2
done
if [ "$health" != "healthy" ]; then
  echo "FAIL: $CONTAINER 未就绪 (health=$health)"
  docker compose logs --tail=60 access-service || true
  exit 1
fi

status=$(docker compose exec -T "$SERVICE" \
  curl -s -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8091/api/v1/smoke/access/register" \
  -H "Content-Type: application/json" -d '{"nickname":"x","username":"x","password":"x"}' || echo "000")
if [ "$status" = "404" ]; then
  echo "PASS: /api/v1/smoke/access 默认禁用 (404)"
  echo "=== Access Smoke Default Disabled Test PASSED ==="
  docker compose stop access-service >/dev/null 2>&1 || true
  exit 0
fi
echo "FAIL: 默认服务意外暴露 smoke 端点 (status=$status)"
docker compose stop access-service >/dev/null 2>&1 || true
exit 1
