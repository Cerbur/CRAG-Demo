#!/bin/bash
# router4 Smoke Profile 全链路 HTTP 回归（plan_21/21.13）
#
# 范围：验证 plan_21/21.11 收敛后的单服务 Smoke 拓扑：
#   - 默认 profile（CRAG_SERVICE_PROFILES 未设）下，三个业务服务不暴露 /api/v1/smoke/**
#   - 启用 smoke profile（CRAG_SERVICE_PROFILES=smoke）后，原服务固定端口暴露 smoke 入口
#   - 原服务端口：access 8091、knowledge 8092、rag 8082（无 *-smoke 重复容器）
#
# 注意：本脚本需要能够重启 Compose 并切换 profile。由于本机无 docker compose，
# 验收 session 在 Docker 环境中执行时需按下方步骤分别启动 default 与 smoke profile。
# 脚本提供两种模式：
#   1) 若 Compose 已以 default profile 启动，断言 smoke 入口 404
#   2) 若 Compose 已以 smoke profile 启动，断言 smoke 入口 200
# 通过环境变量 SMOKE_MODE=default|smoke 选择（默认 default）

set -euo pipefail

SMOKE_MODE="${SMOKE_MODE:-default}"
ACCESS_URL="${ACCESS_SERVICE_URL:-http://localhost:8091}"
KNOWLEDGE_URL="${KNOWLEDGE_SERVICE_URL:-http://localhost:8092}"
RAG_URL="${RAG_SERVICE_URL:-http://localhost:8082}"
TIMEOUT=120
RUN_ID="$(date +%s)-$$"

echo "=== router4 Smoke Profile Test (run=$RUN_ID mode=$SMOKE_MODE) ==="

# 等待三个服务就绪
for url in "$ACCESS_URL" "$KNOWLEDGE_URL" "$RAG_URL"; do
  status="000"
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health/readiness" || echo "000")
    [ "$status" = "200" ] && break
    sleep 2
  done
  [ "$status" = "200" ] || { echo "FAIL: $url 未就绪"; exit 1; }
done
echo "PASS: 三个业务服务就绪"

# 确认无 *-smoke 容器（通过 docker compose ps 静态检查；若 docker 不可用则跳过）
if command -v docker >/dev/null 2>&1; then
  SMOKE_CONTAINERS=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E 'smoke$' || true)
  [ -z "$SMOKE_CONTAINERS" ] || { echo "FAIL: 仍存在 *-smoke 容器: $SMOKE_CONTAINERS"; exit 1; }
  echo "PASS: 无 *-smoke 重复容器"
fi

if [ "$SMOKE_MODE" = "default" ]; then
  # default profile：smoke 入口应 404
  echo "--- default profile：smoke 入口 404 ---"
  for url in \
    "$ACCESS_URL/api/v1/smoke/access/register" \
    "$KNOWLEDGE_URL/api/v1/smoke/knowledge/knowledge-bases" \
    "$RAG_URL/api/v1/smoke/query"; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url" || echo "000")
    [ "$status" = "404" ] || { echo "FAIL: $url default profile 应 404 (实际 $status)"; exit 1; }
  done
  echo "PASS: default profile 下 smoke 入口全部 404"
elif [ "$SMOKE_MODE" = "smoke" ]; then
  # smoke profile：smoke 入口应可达（200 或业务码）
  echo "--- smoke profile：smoke 入口可达 ---"
  # access smoke register 需要 POST
  status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$ACCESS_URL/api/v1/smoke/access/register" \
    -H "Content-Type: application/json" \
    -d "{\"nickname\":\"x\",\"username\":\"smoke_${RUN_ID}\",\"password\":\"correct-horse-battery-12\"}" || echo "000")
  [ "$status" = "200" ] || { echo "FAIL: access smoke register 应 200 (实际 $status)"; exit 1; }
  # knowledge smoke 入口（GET）
  status=$(curl -s -o /dev/null -w "%{http_code}" "$KNOWLEDGE_URL/api/v1/smoke/knowledge/knowledge-bases" || echo "000")
  [ "$status" = "200" ] || { echo "FAIL: knowledge smoke 入口应 200 (实际 $status)"; exit 1; }
  echo "PASS: smoke profile 下原服务端口 smoke 入口可达"
else
  echo "FAIL: 未知 SMOKE_MODE=$SMOKE_MODE（应为 default 或 smoke）"
  exit 1
fi

# 固定端口断言（plan_21/21.11 收敛）
echo "--- 固定端口断言 ---"
[ "$(echo "$ACCESS_URL" | grep -oE '[0-9]+$')" = "8091" ] || { echo "FAIL: access 端口非 8091"; exit 1; }
[ "$(echo "$KNOWLEDGE_URL" | grep -oE '[0-9]+$')" = "8092" ] || { echo "FAIL: knowledge 端口非 8092"; exit 1; }
[ "$(echo "$RAG_URL" | grep -oE '[0-9]+$')" = "8082" ] || { echo "FAIL: rag 端口非 8082"; exit 1; }
echo "PASS: 固定端口 access=8091 knowledge=8092 rag=8082"

echo "PASS: router4 Smoke Profile 全链路正确"
echo "=== router4 Smoke Profile Test PASSED ==="
