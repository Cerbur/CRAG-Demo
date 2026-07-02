#!/bin/bash
# Web Console 认证链路同源代理 HTTP 回归（plan_22/22.9）
#
# 范围：只通过浏览器入口 http://localhost:3000/console-api/** 验证同源代理 + Cookie Path 重写：
#   - register 经代理返回 accessToken，Set-Cookie 的 Path 被重写为 /console-api/api/v1/auth
#   - 同一 Cookie Jar 在后续 /console-api/api/v1/auth/refresh 上回送（证明 Path 作用域正确）
#   - refresh 携带 Origin 通过校验并轮换新 accessToken
#   - refresh 缺 Origin 返回 403
#   - logout 经代理清除 Cookie
#   - me 经代理：无 Bearer 401，携带 Bearer 返回安全投影
#   - register 校验失败经代理返回 400
#
# 用法: bash scripts/tests/http/web_auth_test.sh
# 前置: 完整 Compose 已启动（含 web；console-api/access-service 健康）。本脚本不执行 compose up。
# 数据隔离：使用唯一 RUN_ID 注册账号，不清数据库/Volume。

set -euo pipefail

WEB_URL="${WEB_URL:-http://localhost:3000}"
ORIGIN="${WEB_ORIGIN:-http://localhost:3000}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=120
USERNAME="webauth_${RUN_ID}"
NICKNAME="Web Auth $RUN_ID"

echo "=== Web Auth Proxy Test (run=$RUN_ID) ==="
echo "WEB_URL=$WEB_URL ORIGIN=$ORIGIN"

# 等待 web 就绪。
status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/health" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: web /health 未就绪"; exit 1; }

json_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""; }
json_result_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('$2',''))" 2>/dev/null || echo ""; }
http_code() { echo "$1" | tail -1; }
http_body() { echo "$1" | sed '$d'; }

# --- 1. register 经代理成功 + Cookie Path 重写 ---
echo "--- 1. register 经代理成功，Set-Cookie Path 重写为 /console-api/api/v1/auth ---"
COOKIE_JAR=$(mktemp /tmp/webauth-cookies-XXXX.txt)
HEADER_FILE=$(mktemp /tmp/webauth-headers-XXXX.txt)
raw=$(curl -s -D "$HEADER_FILE" -w '\n%{http_code}' -c "$COOKIE_JAR" -X POST "$WEB_URL/console-api/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"$NICKNAME\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code"; echo "$body"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: register code=$RESP_CODE"; echo "$body"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; }
ACCESS_TOKEN=$(json_result_field "$body" accessToken)
[ -n "$ACCESS_TOKEN" ] || { echo "FAIL: register 无 accessToken"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; }
echo "$body" | grep -q '"refreshToken"' && { echo "FAIL: 响应体泄漏 refreshToken"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; }
# 直接校验浏览器实际收到的原始 Set-Cookie 头：Path 必须被 Runtime Server 重写。
grep -qi 'Set-Cookie:.*Path=/console-api/api/v1/auth' "$HEADER_FILE" \
  || { echo "FAIL: Set-Cookie 未重写 Path=/console-api/api/v1/auth"; cat "$HEADER_FILE"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; }
# 确保不存在未被重写的旧 Path=/api/v1/auth（去掉 console-api 前缀的纯旧路径）。
grep -Ei 'Set-Cookie:.*Path=/api/v1/auth([[:space:]]|$)' "$HEADER_FILE" \
  && { echo "FAIL: 仍存在未被重写的 Set-Cookie Path=/api/v1/auth"; cat "$HEADER_FILE"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; } || true
# Cookie Jar（Netscape 格式，第 3 列为裸路径）同样应为重写后的路径。
awk -F'\t' 'NR>3 && $6=="refresh_token" {print $3}' "$COOKIE_JAR" | grep -qx '/console-api/api/v1/auth' \
  || { echo "FAIL: Cookie Jar 路径未重写"; cat "$COOKIE_JAR"; rm -f "$COOKIE_JAR" "$HEADER_FILE"; exit 1; }
rm -f "$HEADER_FILE"
echo "PASS: register 成功，Cookie Path 重写为 /console-api/api/v1/auth"

# --- 2. refresh 经代理（Cookie Jar 同源回送） ---
echo "--- 2. refresh 经代理使用同源 Cookie 成功 ---"
raw=$(curl -s -w '\n%{http_code}' -b "$COOKIE_JAR" -X POST "$WEB_URL/console-api/api/v1/auth/refresh" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: refresh HTTP $code（Cookie 应被同源回送）"; echo "$body"; rm -f "$COOKIE_JAR"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: refresh code=$RESP_CODE"; echo "$body"; rm -f "$COOKIE_JAR"; exit 1; }
NEW_ACCESS=$(json_result_field "$body" accessToken)
[ -n "$NEW_ACCESS" ] || { echo "FAIL: refresh 无新 accessToken"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: refresh 成功（Cookie Path 重写后浏览器同源回送）"

# --- 3. refresh 缺 Origin 403 ---
echo "--- 3. refresh 缺 Origin 经代理返回 403 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -b "$COOKIE_JAR" -X POST "$WEB_URL/console-api/api/v1/auth/refresh" \
  -H "Content-Type: application/json" || echo "000")
[ "$status" = "403" ] || { echo "FAIL: refresh 缺 Origin 应 403 实际 $status"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: refresh 缺 Origin 403"

# --- 4. logout 经代理 ---
echo "--- 4. logout 经代理成功 ---"
raw=$(curl -s -w '\n%{http_code}' -b "$COOKIE_JAR" -X POST "$WEB_URL/console-api/api/v1/auth/logout" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -H "Authorization: Bearer $NEW_ACCESS" \
  || printf '\n000')
code=$(http_code "$raw")
[ "$code" = "200" ] || { echo "FAIL: logout HTTP $code"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: logout 200"

# --- 5. me 经代理无 Bearer 401 ---
echo "--- 5. me 经代理无 Bearer 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$WEB_URL/console-api/api/v1/auth/me" || echo "000")
[ "$status" = "401" ] || { echo "FAIL: me 无 Bearer 应 401 实际 $status"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: me 无 Bearer 401"

# --- 6. me 经代理携带 Bearer 返回安全投影 ---
echo "--- 6. me 经代理携带 Bearer 返回安全投影 ---"
raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $ACCESS_TOKEN" "$WEB_URL/console-api/api/v1/auth/me" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: me HTTP $code"; echo "$body"; rm -f "$COOKIE_JAR"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: me code=$RESP_CODE"; rm -f "$COOKIE_JAR"; exit 1; }
echo "$body" | grep -q '"password"' && { echo "FAIL: me 响应泄漏 password"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: me 返回安全投影（无 password）"

# --- 7. register 校验失败经代理 400 ---
echo "--- 7. register 校验失败经代理 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$WEB_URL/console-api/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d '{"nickname":"x","username":"ab","password":"short"}' || echo "000")
[ "$status" = "400" ] || { echo "FAIL: register 校验失败应 400 实际 $status"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: register 校验失败 400"

rm -f "$COOKIE_JAR"
echo "PASS: Web 认证同源代理全链路正确"
echo "=== Web Auth Proxy Test PASSED ==="
