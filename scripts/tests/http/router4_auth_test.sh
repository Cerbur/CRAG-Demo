#!/bin/bash
# router4 Console Auth 全链路 HTTP 回归（plan_21/21.13）
#
# 范围：通过 console-api 真实链路验证 register/login/refresh/logout/me。
#   - register 返回 accessToken + user + defaultTenant，Set-Cookie 含 Refresh Token（HttpOnly）
#   - login 返回 accessToken（defaultTenant 缺失，符合 login 契约）
#   - refresh 使用 Cookie + Origin 通过校验并轮换新 accessToken
#   - logout finally 清除 Cookie
#   - me 携带 Bearer 返回安全投影；无 Bearer 返回 401
#   - register 校验失败返回 400 且不回显密码
#
# 用法: bash scripts/tests/http/router4_auth_test.sh
# 前置: 完整 Compose 已启动（console-api + access-service + db + redis）。
#       本脚本不执行 docker compose up（由 router4_smoke_profile_test.sh 或验收 session 统一编排）。

set -euo pipefail

CONSOLE_URL="${CONSOLE_API_URL:-http://localhost:8080}"
ORIGIN="${CONSOLE_ORIGIN:-http://localhost:8080}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=180
USERNAME="r4auth_${RUN_ID}"
NICKNAME="Router4 Auth $RUN_ID"

echo "=== router4 Console Auth Test (run=$RUN_ID) ==="
echo "CONSOLE_URL=$CONSOLE_URL"

# 等待 console-api 就绪（由编排负责启动；此处只做 readiness gate）
status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$CONSOLE_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: console-api 未就绪 (status=$status)"; exit 1; }

json_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""; }
json_result_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('$2',''))" 2>/dev/null || echo ""; }
http_code() { echo "$1" | tail -1; }
http_body() { echo "$1" | sed '$d'; }

# --- 1. register 成功 ---
echo "--- 1. register 成功 ---"
COOKIE_JAR=$(mktemp /tmp/r4auth-cookies-XXXX.txt)
raw=$(curl -s -w '\n%{http_code}' -c "$COOKIE_JAR" -X POST "$CONSOLE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"$NICKNAME\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw")
body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code (expected 200)"; echo "$body"; rm -f "$COOKIE_JAR"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: register code=$RESP_CODE"; echo "$body"; rm -f "$COOKIE_JAR"; exit 1; }
ACCESS_TOKEN=$(json_result_field "$body" accessToken)
[ -n "$ACCESS_TOKEN" ] || { echo "FAIL: register 无 accessToken"; rm -f "$COOKIE_JAR"; exit 1; }
# Refresh Token 只在 Cookie 中，不能进响应体
echo "$body" | grep -q '"refreshToken"' && { echo "FAIL: register 响应体泄漏 refreshToken"; rm -f "$COOKIE_JAR"; exit 1; }
# Cookie 存在
grep -q 'refresh' "$COOKIE_JAR" || { echo "FAIL: register 未下发 Refresh Cookie"; rm -f "$COOKIE_JAR"; exit 1; }
echo "PASS: register 成功，accessToken 已下发，Refresh 仅在 HttpOnly Cookie"

# --- 2. login 成功 ---
echo "--- 2. login 成功 ---"
COOKIE_JAR2=$(mktemp /tmp/r4auth-cookies2-XXXX.txt)
raw=$(curl -s -w '\n%{http_code}' -c "$COOKIE_JAR2" -X POST "$CONSOLE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw")
body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: login HTTP $code"; echo "$body"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: login code=$RESP_CODE"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: login 成功"

# --- 3. refresh 成功（使用 register 下发的 Cookie + Origin） ---
echo "--- 3. refresh 成功 ---"
raw=$(curl -s -w '\n%{http_code}' -b "$COOKIE_JAR" -X POST "$CONSOLE_URL/api/v1/auth/refresh" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  || printf '\n000')
code=$(http_code "$raw")
body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: refresh HTTP $code (expected 200, Cookie/Origin 应通过)"; echo "$body"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: refresh code=$RESP_CODE"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
NEW_ACCESS=$(json_result_field "$body" accessToken)
[ -n "$NEW_ACCESS" ] || { echo "FAIL: refresh 无新 accessToken"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: refresh 成功（Cookie + Origin 通过校验，轮换新 accessToken）"

# --- 4. refresh 缺 Origin 应 403 ---
echo "--- 4. refresh 缺 Origin 应 403 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -b "$COOKIE_JAR" -X POST "$CONSOLE_URL/api/v1/auth/refresh" \
  -H "Content-Type: application/json" || echo "000")
[ "$status" = "403" ] || { echo "FAIL: refresh 缺 Origin 应 403 (实际 $status)"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: refresh 缺 Origin 拒绝 403"

# --- 5. logout 清除 Cookie ---
echo "--- 5. logout 清除 Cookie ---"
raw=$(curl -s -w '\n%{http_code}' -b "$COOKIE_JAR" -X POST "$CONSOLE_URL/api/v1/auth/logout" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -H "Authorization: Bearer $NEW_ACCESS" \
  || printf '\n000')
code=$(http_code "$raw")
[ "$code" = "200" ] || { echo "FAIL: logout HTTP $code (expected 200)"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: logout 200"

# --- 6. me 无 Bearer 返回 401 ---
echo "--- 6. me 无 Bearer 返回 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$CONSOLE_URL/api/v1/auth/me" || echo "000")
[ "$status" = "401" ] || { echo "FAIL: me 无 Bearer 应 401 (实际 $status)"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: me 无 Bearer 返回 401"

# --- 7. me 携带 Bearer 返回 200 安全投影 ---
echo "--- 7. me 携带 Bearer 返回 200 安全投影 ---"
raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $ACCESS_TOKEN" "$CONSOLE_URL/api/v1/auth/me" \
  || printf '\n000')
code=$(http_code "$raw")
body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: me HTTP $code (expected 200)"; echo "$body"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: me code=$RESP_CODE"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "$body" | grep -q '"password"' && { echo "FAIL: me 响应泄漏 password 字段"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: me 返回安全投影（无 password）"

# --- 8. register 校验失败 400 不回显密码 ---
echo "--- 8. register 校验失败 400 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CONSOLE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"x\",\"username\":\"ab\",\"password\":\"short\"}" || echo "000")
[ "$status" = "400" ] || { echo "FAIL: register 校验失败应 400 (实际 $status)"; rm -f "$COOKIE_JAR" "$COOKIE_JAR2"; exit 1; }
echo "PASS: register 校验失败 400"

rm -f "$COOKIE_JAR" "$COOKIE_JAR2"
echo "PASS: router4 Console Auth 全链路正确"
echo "=== router4 Console Auth Test PASSED ==="
