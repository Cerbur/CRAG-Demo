#!/bin/bash
# CRAG-Demo Docker Readiness Regression
# 验证 Actuator 健康端点、最小暴露面、双 App 并存、数据库故障恢复和 bind mount 持久化。
#
# 用法: bash scripts/tests/http/docker_readiness_test.sh
#
# 要求: Docker Compose 栈可正常构建和运行。
# 注意: 此脚本会停止并恢复 db 容器以测试故障恢复；使用 trap 确保环境恢复。

set -euo pipefail

RUN_ID="readiness-$(date +%s)-$$"
FAILED=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

cd "$PROJECT_ROOT"

echo "=== Docker Readiness Test ==="
echo "RUN_ID=$RUN_ID"
echo "PROJECT_ROOT=$PROJECT_ROOT"

# ─── 工具函数 ───

# 等待容器健康状态（有上限轮询）
wait_for_healthy() {
  local service="$1"
  local max_wait="${2:-120}"
  local elapsed=0
  echo "等待 $service 变为 healthy（最多 ${max_wait}s）..."
  while [ $elapsed -lt "$max_wait" ]; do
    local health
    health=$(docker compose ps --format json "$service" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
    if [ "$health" = "healthy" ]; then
      echo "  $service 已 healthy（${elapsed}s）"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo "  FAIL: $service 在 ${max_wait}s 内未变为 healthy"
  return 1
}

# 等待容器变为 unhealthy
wait_for_unhealthy() {
  local service="$1"
  local max_wait="${2:-60}"
  local elapsed=0
  echo "等待 $service 变为 unhealthy（最多 ${max_wait}s）..."
  while [ $elapsed -lt "$max_wait" ]; do
    local health
    health=$(docker compose ps --format json "$service" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
    if [ "$health" = "unhealthy" ]; then
      echo "  $service 已 unhealthy（${elapsed}s）"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo "  FAIL: $service 在 ${max_wait}s 内未变为 unhealthy"
  return 1
}

# 等待 HTTP 端点返回指定状态码（有上限轮询）
wait_for_http_status() {
  local url="$1"
  local expected="$2"
  local desc="$3"
  local max_wait="${4:-60}"
  local elapsed=0
  echo "等待 $desc 返回 HTTP ${expected}（最多 ${max_wait}s）..."
  while [ $elapsed -lt "$max_wait" ]; do
    local status
    status=$(curl -s -m 35 -o /dev/null -w "%{http_code}" "$url" 2>/dev/null) || status="000"
    if [ "$status" = "$expected" ]; then
      echo "  $desc 已返回 HTTP ${expected}（${elapsed}s）"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo "  FAIL: $desc 在 ${max_wait}s 内未返回 HTTP ${expected}（最后状态: ${status}）"
  return 1
}

# 检查 HTTP 端点状态码
check_http_status() {
  local url="$1"
  local expected="$2"
  local desc="$3"
  local status
  status=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$url" 2>/dev/null) || status="000"
  if [ "$status" = "$expected" ]; then
    echo "  PASS: $desc → $status"
    return 0
  else
    echo "  FAIL: $desc → expected $expected, got $status"
    return 1
  fi
}

# 检查健康端点状态和内容（单次，带 curl 超时）
check_health_endpoint() {
  local port="$1"
  local path="$2"
  local expected_status="$3"
  local desc="$4"
  local curl_timeout="${5:-10}"
  local response status body

  response=$(curl -s -m "$curl_timeout" -w "\n%{http_code}" "http://localhost:$port$path" 2>/dev/null) || response=$'\n000'
  status=$(echo "$response" | tail -1)
  body=$(echo "$response" | sed '$d')

  if [ "$status" != "$expected_status" ]; then
    echo "  FAIL: $desc → expected HTTP $expected_status, got $status"
    return 1
  fi

  if [ "$expected_status" = "200" ]; then
    if ! echo "$body" | grep -q '"status":"UP"'; then
      echo "  FAIL: $desc → status≠UP, body=$body"
      return 1
    fi
    if echo "$body" | grep -q '"components"'; then
      echo "  FAIL: $desc → contains components detail"
      return 1
    fi
  fi

  echo "  PASS: $desc → HTTP $status"
  return 0
}

# 等待健康端点返回指定状态码并验证内容（有上限轮询）
wait_for_health_endpoint() {
  local port="$1"
  local path="$2"
  local expected_status="$3"
  local desc="$4"
  local max_wait="${5:-60}"
  local curl_timeout="${6:-10}"
  local elapsed=0
  echo "等待 $desc 返回 HTTP ${expected_status}（最多 ${max_wait}s）..."
  while [ $elapsed -lt "$max_wait" ]; do
    local response status body
    response=$(curl -s -m "$curl_timeout" -w "\n%{http_code}" "http://localhost:$port$path" 2>/dev/null) || response=$'\n000'
    status=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')
    if [ "$status" = "$expected_status" ]; then
      if [ "$expected_status" = "200" ]; then
        if echo "$body" | grep -q '"status":"UP"' && ! echo "$body" | grep -q '"components"'; then
          echo "  $desc 已返回 HTTP ${expected_status}（${elapsed}s）"
          return 0
        fi
      else
        echo "  $desc 已返回 HTTP ${expected_status}（${elapsed}s）"
        return 0
      fi
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo "  FAIL: $desc 在 ${max_wait}s 内未返回 HTTP ${expected_status}（最后状态: ${status:-none}）"
  return 1
}

# 恢复环境的 trap
cleanup_on_exit() {
  local exit_code=$?
  echo ""
  echo "=== 清理阶段 ==="

  # 如果测试失败，先保留日志（在 down 之前捕获）
  if [ $exit_code -ne 0 ]; then
    echo ""
    echo "=== 测试失败，保留日志 ==="
    docker compose --profile smoke logs --tail=80 2>/dev/null || true
    echo "=== app 日志 ==="
    docker compose logs --tail=50 app 2>/dev/null || true
    echo "=== app-smoke 日志 ==="
    docker compose --profile smoke logs --tail=50 app-smoke 2>/dev/null || true
    echo "=== db 日志 ==="
    docker compose logs --tail=20 db 2>/dev/null || true
  fi

  # 恢复 db（如果已停止）
  if docker compose ps db --format json 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if d.get('State','')=='exited' else 1)" 2>/dev/null; then
    echo "恢复 db 容器..."
    docker compose start db || true
    wait_for_healthy db 60 || true
    # 等待 App 恢复
    sleep 10
    wait_for_healthy app 120 || true
  fi

  echo "执行 docker compose down..."
  docker compose --profile smoke down 2>/dev/null || docker compose down 2>/dev/null || true

  exit $exit_code
}

trap cleanup_on_exit EXIT

# ─── 测试 1: Compose 配置校验 ───

echo ""
echo "=== 测试 1: Compose 配置校验 ==="

# 默认配置不包含 app-smoke
default_services=$(docker compose config --services 2>/dev/null)
if echo "$default_services" | grep -q "app-smoke"; then
  echo "  FAIL: 默认配置不应包含 app-smoke"
  FAILED=1
else
  echo "  PASS: 默认配置不包含 app-smoke"
fi

# Smoke 配置包含 app-smoke
smoke_services=$(docker compose --profile smoke config --services 2>/dev/null)
if echo "$smoke_services" | grep -q "app-smoke"; then
  echo "  PASS: Smoke 配置包含 app-smoke"
else
  echo "  FAIL: Smoke 配置应包含 app-smoke"
  FAILED=1
fi

# 两份配置中的 App 服务均包含 healthcheck
for svc in app app-smoke; do
  if docker compose --profile smoke config 2>/dev/null | sed -n "/^  $svc:/,/^  [a-z]/p" | grep -q "healthcheck"; then
    echo "  PASS: $svc 包含 healthcheck"
  else
    echo "  FAIL: $svc 应包含 healthcheck"
    FAILED=1
  fi
done

if [ $FAILED -ne 0 ]; then
  echo "配置校验失败，中止测试"
  exit 1
fi

# ─── 测试 2: 默认栈启动 ───

echo ""
echo "=== 测试 2: 默认栈启动 ==="

echo "构建并启动默认栈..."
docker compose up -d --build --wait 2>&1 | tail -5

# 等待所有服务健康
for svc in db sidecar app; do
  if ! wait_for_healthy "$svc" 180; then
    FAILED=1
  fi
done

# ─── 测试 3: App 健康端点验证 ───

echo ""
echo "=== 测试 3: App 健康端点验证 ==="

# /actuator/health 返回 200 + status=UP + 无 components
check_health_endpoint 8080 "/actuator/health" "200" "app /actuator/health" || FAILED=1

# /actuator/health/liveness 返回 200 + status=UP
check_health_endpoint 8080 "/actuator/health/liveness" "200" "app /actuator/health/liveness" || FAILED=1

# /actuator/health/readiness 返回 200 + status=UP
check_health_endpoint 8080 "/actuator/health/readiness" "200" "app /actuator/health/readiness" || FAILED=1

# /actuator/env 返回 404（不暴露）
check_http_status "http://localhost:8080/actuator/env" "404" "app /actuator/env" || FAILED=1

# /api/v1/test/smoke 返回 404（默认模式不暴露 Smoke 端点）
check_http_status "http://localhost:8080/api/v1/test/smoke" "404" "app /api/v1/test/smoke" || FAILED=1

# ─── 测试 4: 启用 app-smoke，双 App 并存 ───

echo ""
echo "=== 测试 4: 启用 app-smoke，双 App 并存 ==="

echo "启动 app-smoke..."
docker compose --profile smoke up -d --build app-smoke 2>&1 | tail -5

# 等待 app-smoke 健康
if ! wait_for_healthy "app-smoke" 120; then
  FAILED=1
fi

# 两个 App 同时 healthy
for svc in app app-smoke; do
  local_health=$(docker compose ps --format json "$svc" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
  if [ "$local_health" = "healthy" ]; then
    echo "  PASS: $svc 同时 healthy"
  else
    echo "  FAIL: $svc 应为 healthy，实际=$local_health"
    FAILED=1
  fi
done

# 8080 和 8081 的正式健康端点均成功
check_health_endpoint 8080 "/actuator/health" "200" "app:8080 /actuator/health" || FAILED=1
check_health_endpoint 8081 "/actuator/health" "200" "app-smoke:8081 /actuator/health" || FAILED=1

# 只有 8081 的 Smoke 诊断端点成功
check_http_status "http://localhost:8081/api/v1/test/smoke" "200" "app-smoke:8081 /api/v1/test/smoke" || FAILED=1
check_http_status "http://localhost:8080/api/v1/test/smoke" "404" "app:8080 /api/v1/test/smoke (应 404)" || FAILED=1

# ─── 测试 5: AdminRag 写入 ───

echo ""
echo "=== 测试 5: AdminRag 写入 ==="

# 通过 POST /api/v1/admin/rag 写入标题含 runId 的文档
admin_response=$(curl -s -m 10 -X POST "http://localhost:8080/api/v1/admin/rag" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"readiness-test-$RUN_ID\",\"content\":\"Docker readiness regression test document\",\"metadata\":{\"source\":\"readiness-test\"}}" 2>/dev/null)

echo "  AdminRag 响应: $admin_response"

# 提取 docId
doc_id=$(echo "$admin_response" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r.get('result',{}).get('docId','') or r.get('data',{}).get('docId',''))" 2>/dev/null || echo "")

if [ -n "$doc_id" ] && [ "$doc_id" != "None" ] && [ "$doc_id" != "" ]; then
  echo "  PASS: 文档写入成功，docId=$doc_id"
else
  echo "  FAIL: 无法提取 docId，响应=$admin_response"
  FAILED=1
fi

# ─── 测试 6: 数据库故障恢复 ───

echo ""
echo "=== 测试 6: 数据库故障恢复 ==="

echo "停止 db 容器..."
docker compose stop db

# 等待两个 App readiness 变为 503（有上限轮询）
for port in 8080 8081; do
  if ! wait_for_http_status "http://localhost:$port/actuator/health/readiness" "503" ":$port readiness" 120; then
    FAILED=1
  fi
done

# liveness 仍为 200（有上限轮询，curl 超时 10s）
for port in 8080 8081; do
  if ! wait_for_health_endpoint "$port" "/actuator/health/liveness" "200" ":$port liveness (db 停止)" 60 10; then
    FAILED=1
  fi
done

# 容器状态变为 unhealthy（healthcheck retries=12, interval=10s，最多 120s）
for svc in app app-smoke; do
  if ! wait_for_unhealthy "$svc" 150; then
    FAILED=1
  fi
done

# 恢复 db
echo "恢复 db 容器..."
docker compose start db
if ! wait_for_healthy db 60; then
  FAILED=1
fi

# 等待两个 App 重新 healthy
sleep 10
for svc in app app-smoke; do
  if ! wait_for_healthy "$svc" 120; then
    FAILED=1
  fi
done

# ─── 测试 7: 持久化验证 ───

echo ""
echo "=== 测试 7: 持久化验证 ==="

echo "执行 docker compose down..."
docker compose --profile smoke down

echo "重新启动默认栈..."
docker compose up -d --build --wait 2>&1 | tail -5

# 等待健康
for svc in db app; do
  if ! wait_for_healthy "$svc" 180; then
    FAILED=1
  fi
done

# 只读 SQL 查询确认文档仍存在
if [ -n "$doc_id" ] && [ "$doc_id" != "None" ] && [ "$doc_id" != "" ]; then
  echo "查询数据库验证文档持久化..."
  db_result=$(docker compose exec -T db psql -U crag_user -d crag_demo -t -A -c "SELECT count(*) FROM chunk WHERE doc_id = '$doc_id';" 2>&1 || echo "ERROR")
  echo "  DB 查询结果: $db_result"

  if echo "$db_result" | grep -q "^[1-9]"; then
    echo "  PASS: 文档 $doc_id 在 down/up 后仍存在于数据库"
  else
    echo "  FAIL: 文档 $doc_id 在 down/up 后未找到"
    FAILED=1
  fi
else
  echo "  SKIP: 无有效 docId，跳过持久化验证"
fi

# ─── 最终清理 ───

echo ""
echo "=== 最终清理 ==="
docker compose down

# ─── 结果 ───

echo ""
if [ $FAILED -eq 0 ]; then
  echo "=== Docker Readiness Test PASSED ==="
  exit 0
else
  echo "=== Docker Readiness Test FAILED ==="
  exit 1
fi
