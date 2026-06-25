#!/bin/bash
# CRAG-Demo Event Smoke HTTP Regression — 默认 Profile 禁用
# 验证默认 Knowledge 服务（无 smoke Profile）不暴露 /api/v1/smoke/events。
# 自动启动 db/knowledge-service（默认），通过容器内 curl 断言端点 404。
#
# 用法: bash scripts/tests/http/event_smoke_default_disabled_test.sh

set -euo pipefail

TIMEOUT=120
CONTAINER="crag-knowledge-service"
SERVICE="knowledge-service"

echo "=== Event Smoke Default Disabled Test ==="

# 默认 knowledge-service 不在 smoke profile；启动它和 db。
docker compose up -d --build db knowledge-service

echo "waiting for knowledge-service readiness..."
health="starting"
for _ in $(seq 1 "$TIMEOUT"); do
  health=$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "missing")
  [ "$health" = "healthy" ] && break
  sleep 2
done
if [ "$health" != "healthy" ]; then
  echo "FAIL: $CONTAINER 未就绪 (health=$health)"
  docker compose logs --tail=60 knowledge-service || true
  exit 1
fi

# 容器内访问 smoke 端点，应返回 404（默认未注册）。
# docker compose exec 解析的是 service 名而非 container 名，故用 $SERVICE。
status=$(docker compose exec -T "$SERVICE" \
  curl -s -o /dev/null -w "%{http_code}" "http://localhost:8092/api/v1/smoke/events/test" || echo "000")

if [ "$status" = "404" ]; then
  echo "PASS: /api/v1/smoke/events 默认禁用 (404)"
  echo "=== Event Smoke Default Disabled Test PASSED ==="
  docker compose stop knowledge-service >/dev/null 2>&1 || true
  exit 0
fi

echo "FAIL: 默认服务意外暴露 smoke 端点 (status=$status)"
docker compose stop knowledge-service >/dev/null 2>&1 || true
exit 1
