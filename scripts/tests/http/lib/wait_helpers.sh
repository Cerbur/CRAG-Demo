#!/bin/bash
# scripts/tests/http/lib/wait_helpers.sh
#
# Deadline-bounded polling helpers shared by Docker readiness regression scripts.
#
# plan_10.hotfix_1：修复原内联轮询中 elapsed 只累加固定 sleep、未计入 curl -m 实际阻塞时间
# 的缺陷。当被等待端点 hang（如 db 故障下 readiness 因 health indicator 阻塞）时，每轮
# ~curl_timeout+sleep 只让 elapsed 增加 sleep，导致真实墙钟耗时随循环次数成倍放大
# （max_wait=120 实际 ~16 分钟）。
#
# 解决方式：以 `date +%s` 计算 start 与绝对 deadline = start + max_wait，每轮先用真实 elapsed
# 判断是否超时，再把本轮 curl timeout 和 sleep 裁剪到剩余秒数；剩余整秒不足 1 时直接判定
# 超时，不再发起请求，避免零/负参数或忙循环。
#
# 本文件只封装轮询机制，不承载测试步骤、服务名、URL 或业务断言。
#
# 公开函数（参数顺序与返回码保持兼容）：
#   wait_for_healthy         <service> [max_wait]
#   wait_for_unhealthy       <service> [max_wait]
#   wait_for_http_status     <url> <expected> <desc> [max_wait]
#   wait_for_health_endpoint <port> <path> <expected_status> <desc> [max_wait] [curl_timeout]

# 等待容器健康状态（有上限墙钟轮询）
wait_for_healthy() {
  local service="$1"
  local max_wait="${2:-120}"
  local start elapsed remaining sleep_time health
  start=$(date +%s)
  echo "等待 $service 变为 healthy（最多 ${max_wait}s）..."
  while :; do
    elapsed=$(($(date +%s) - start))
    if [ "$elapsed" -ge "$max_wait" ]; then
      break
    fi
    health=$(docker compose ps --format json "$service" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
    if [ "$health" = "healthy" ]; then
      echo "  $service 已 healthy（${elapsed}s）"
      return 0
    fi
    elapsed=$(($(date +%s) - start))
    remaining=$((max_wait - elapsed))
    if [ "$remaining" -lt 1 ]; then
      break
    fi
    sleep_time=5
    [ "$sleep_time" -gt "$remaining" ] && sleep_time=$remaining
    sleep "$sleep_time"
  done
  elapsed=$(($(date +%s) - start))
  echo "  FAIL: $service 在 ${max_wait}s 内未变为 healthy（实际 ${elapsed}s）"
  return 1
}

# 等待容器变为 unhealthy（有上限墙钟轮询）
wait_for_unhealthy() {
  local service="$1"
  local max_wait="${2:-60}"
  local start elapsed remaining sleep_time health
  start=$(date +%s)
  echo "等待 $service 变为 unhealthy（最多 ${max_wait}s）..."
  while :; do
    elapsed=$(($(date +%s) - start))
    if [ "$elapsed" -ge "$max_wait" ]; then
      break
    fi
    health=$(docker compose ps --format json "$service" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
    if [ "$health" = "unhealthy" ]; then
      echo "  $service 已 unhealthy（${elapsed}s）"
      return 0
    fi
    elapsed=$(($(date +%s) - start))
    remaining=$((max_wait - elapsed))
    if [ "$remaining" -lt 1 ]; then
      break
    fi
    sleep_time=5
    [ "$sleep_time" -gt "$remaining" ] && sleep_time=$remaining
    sleep "$sleep_time"
  done
  elapsed=$(($(date +%s) - start))
  echo "  FAIL: $service 在 ${max_wait}s 内未变为 unhealthy（实际 ${elapsed}s）"
  return 1
}

# 等待 HTTP 端点返回指定状态码（有上限墙钟轮询）
# curl timeout 固定 35s，但被 deadline 裁剪到剩余秒数；端点 hang 时不会成倍超出 max_wait。
wait_for_http_status() {
  local url="$1"
  local expected="$2"
  local desc="$3"
  local max_wait="${4:-60}"
  local curl_max=35
  local start elapsed remaining this_curl sleep_time status
  start=$(date +%s)
  echo "等待 $desc 返回 HTTP ${expected}（最多 ${max_wait}s）..."
  while :; do
    elapsed=$(($(date +%s) - start))
    if [ "$elapsed" -ge "$max_wait" ]; then
      break
    fi
    remaining=$((max_wait - elapsed))
    if [ "$remaining" -lt 1 ]; then
      break
    fi
    this_curl=$remaining
    [ "$this_curl" -gt "$curl_max" ] && this_curl=$curl_max
    status=$(curl -s -m "$this_curl" -o /dev/null -w "%{http_code}" "$url" 2>/dev/null) || status="000"
    if [ "$status" = "$expected" ]; then
      elapsed=$(($(date +%s) - start))
      echo "  $desc 已返回 HTTP ${expected}（${elapsed}s）"
      return 0
    fi
    elapsed=$(($(date +%s) - start))
    remaining=$((max_wait - elapsed))
    if [ "$remaining" -lt 1 ]; then
      break
    fi
    sleep_time=5
    [ "$sleep_time" -gt "$remaining" ] && sleep_time=$remaining
    sleep "$sleep_time"
  done
  elapsed=$(($(date +%s) - start))
  echo "  FAIL: $desc 在 ${max_wait}s 内未返回 HTTP ${expected}（最后状态: ${status:-none}，实际 ${elapsed}s）"
  return 1
}

# 等待健康端点返回指定状态码并验证内容（有上限墙钟轮询）
# 200 时额外校验响应体含 "status":"UP"；curl timeout（默认 10s）与 sleep 均裁剪到剩余秒数。
wait_for_health_endpoint() {
  local port="$1"
  local path="$2"
  local expected_status="$3"
  local desc="$4"
  local max_wait="${5:-60}"
  local curl_max="${6:-10}"
  local start elapsed remaining this_curl sleep_time response status body
  start=$(date +%s)
  echo "等待 $desc 返回 HTTP ${expected_status}（最多 ${max_wait}s）..."
  while :; do
    elapsed=$(($(date +%s) - start))
    if [ "$elapsed" -ge "$max_wait" ]; then
      break
    fi
    remaining=$((max_wait - elapsed))
    if [ "$remaining" -lt 1 ]; then
      break
    fi
    this_curl=$remaining
    [ "$this_curl" -gt "$curl_max" ] && this_curl=$curl_max
    response=$(curl -s -m "$this_curl" -w "\n%{http_code}" "http://localhost:$port$path" 2>/dev/null) || response=$'\n000'
    status=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')
    if [ "$status" = "$expected_status" ]; then
      if [ "$expected_status" = "200" ]; then
        if echo "$body" | grep -q '"status":"UP"'; then
          elapsed=$(($(date +%s) - start))
          echo "  $desc 已返回 HTTP ${expected_status}（${elapsed}s）"
          return 0
        fi
      else
        elapsed=$(($(date +%s) - start))
        echo "  $desc 已返回 HTTP ${expected_status}（${elapsed}s）"
        return 0
      fi
    fi
    elapsed=$(($(date +%s) - start))
    remaining=$((max_wait - elapsed))
    if [ "$remaining" -lt 1 ]; then
      break
    fi
    sleep_time=5
    [ "$sleep_time" -gt "$remaining" ] && sleep_time=$remaining
    sleep "$sleep_time"
  done
  elapsed=$(($(date +%s) - start))
  echo "  FAIL: $desc 在 ${max_wait}s 内未返回 HTTP ${expected_status}（最后状态: ${status:-none}，实际 ${elapsed}s）"
  return 1
}
