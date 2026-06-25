#!/bin/bash
# Knowledge smoke HTTP 回归 — 默认 Profile 禁用
# 验证默认 Knowledge 服务（无 smoke Profile）不暴露 /api/v1/smoke/knowledge/**。
# 通过容器内 curl 断言 POST 端点返回 404。
#
# 用法: bash scripts/tests/http/knowledge_smoke_default_disabled_test.sh

set -euo pipefail

TIMEOUT=120
CONTAINER="crag-knowledge-service"
SERVICE="knowledge-service"

echo "=== Knowledge Smoke Default Disabled Test ==="

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

status=$(docker compose exec -T "$SERVICE" \
  curl -s -o /dev/null -w "%{http_code}" -X POST \
  "http://localhost:8092/api/v1/smoke/knowledge/knowledge-bases" \
  -H "Content-Type: application/json" -d '{}' || echo "000")

if [ "$status" = "404" ]; then
  echo "PASS: /api/v1/smoke/knowledge 默认禁用 (404)"
  echo "=== Knowledge Smoke Default Disabled Test PASSED ==="
  docker compose stop knowledge-service >/dev/null 2>&1 || true
  exit 0
fi

echo "FAIL: 默认服务意外暴露 smoke 端点 (status=$status)"
docker compose stop knowledge-service >/dev/null 2>&1 || true
exit 1
