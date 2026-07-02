#!/bin/bash
# Web Console 容器与同源代理 HTTP 回归（plan_22/22.9）
#
# 范围：只通过浏览器入口 http://localhost:3000 验证 Runtime Server：
#   - /health 返回 200 {"status":"UP"}
#   - 根路径返回 index.html（text/html，含 SPA root）
#   - 深链 /app/knowledge 命中 SPA 回退（200 text/html）
#   - 缺失静态资源 /assets/missing.js 返回 404（不回退）
#   - /console-api/ 与 /open-api/ 同源代理真实穿透到后端 readiness
#   - 容器以非 root 身份运行；PID 1 为 node server/server.mjs（非 vite preview）
#
# 用法: bash scripts/tests/http/web_container_test.sh
# 前置: 完整 Compose 已启动（含 web；console-api/open-api 健康）。本脚本不执行 compose up。
# HTTP 业务入口为主证据；docker compose exec 仅作非 root / 进程身份的辅助诊断。

set -euo pipefail

WEB_URL="${WEB_URL:-http://localhost:3000}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=120

echo "=== Web Container Test (run=$RUN_ID) ==="
echo "WEB_URL=$WEB_URL"

# 等待 web /health 就绪（编排负责启动；此处只做 readiness gate）。
status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/health" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: web /health 未就绪 (status=$status)"; exit 1; }

# --- 1. /health 内容 ---
echo "--- 1. /health 返回 UP ---"
body=$(curl -s "$WEB_URL/health" || echo "")
echo "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' \
  || { echo "FAIL: /health 内容异常: $body"; exit 1; }
echo "PASS: /health UP"

# --- 2. 根路径返回 index.html ---
echo "--- 2. 根路径托管 index.html ---"
raw=$(curl -s -w '\n%{http_code}' "$WEB_URL/" || printf '\n000')
code=$(echo "$raw" | tail -1)
html=$(echo "$raw" | sed '$d')
[ "$code" = "200" ] || { echo "FAIL: / HTTP $code"; exit 1; }
echo "$html" | grep -qi '<html' || { echo "FAIL: / 未返回 HTML"; exit 1; }
echo "$html" | grep -q 'id="root"' || { echo "FAIL: / 未包含 SPA root 容器"; exit 1; }
echo "PASS: / 返回 SPA index.html"

# --- 3. 深链 SPA 回退 ---
echo "--- 3. 深链 /app/knowledge 命中 SPA 回退 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/app/knowledge/kb_stub" || echo "000")
[ "$status" = "200" ] || { echo "FAIL: 深链应 200（SPA 回退）实际 $status"; exit 1; }
echo "PASS: 深链回退 index.html"

# --- 4. 缺失静态资源 404 ---
echo "--- 4. 缺失静态资源 /assets/missing.js 返回 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/assets/missing.js" || echo "000")
[ "$status" = "404" ] || { echo "FAIL: 缺失资源应 404 实际 $status"; exit 1; }
echo "PASS: 缺失资源 404"

# --- 5. /console-api/ 代理穿透 ---
echo "--- 5. /console-api/ 同源代理穿透 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/console-api/actuator/health/readiness" || echo "000")
[ "$status" = "200" ] || { echo "FAIL: console-api 代理应 200 实际 $status"; exit 1; }
echo "PASS: /console-api/ 代理 console-api readiness"

# --- 6. /open-api/ 代理穿透 ---
echo "--- 6. /open-api/ 同源代理穿透 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/open-api/actuator/health/readiness" || echo "000")
[ "$status" = "200" ] || { echo "FAIL: open-api 代理应 200 实际 $status"; exit 1; }
echo "PASS: /open-api/ 代理 open-api readiness"

# --- 7. 容器非 root（辅助诊断） ---
echo "--- 7. 容器以非 root 身份运行 ---"
uid=$(docker compose exec -T web sh -c 'id -u' 2>/dev/null | tr -d '[:space:]') || uid=""
[ -n "$uid" ] && [ "$uid" != "0" ] \
  || { echo "FAIL: web 容器应以非 root 运行 (uid=$uid)"; exit 1; }
echo "PASS: web 非 root (uid=$uid)"

# --- 8. PID 1 为 node server/server.mjs（非 vite preview） ---
echo "--- 8. Runtime Server 为 node server.mjs（非 vite preview） ---"
cmdline=$(docker compose exec -T web sh -c 'tr "\0" " " < /proc/1/cmdline' 2>/dev/null || echo "")
echo "$cmdline" | grep -q 'server/server.mjs' \
  || { echo "FAIL: PID 1 不是 node server/server.mjs: $cmdline"; exit 1; }
echo "$cmdline" | grep -qi 'vite' \
  && { echo "FAIL: PID 1 命中 vite，禁止使用 vite preview: $cmdline"; exit 1; } || true
echo "PASS: Runtime Server = node server/server.mjs"

echo "PASS: Web 容器与同源代理全链路正确"
echo "=== Web Container Test PASSED ==="
