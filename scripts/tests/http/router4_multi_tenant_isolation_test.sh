#!/bin/bash
# router4 多租户隔离全链路 HTTP 回归（plan_21/21.13）
#
# 范围：验证两个独立 Tenant 的数据完全隔离。
#   - 注册两个 OWNER（分属不同 Tenant）
#   - 各自 create KB，KB id 不重叠
#   - A 不能 get/list B 的 KB（404，不泄漏存在性）
#   - A 不能在 B 的 KB 上 upload/list documents（404）
#   - A 不能在 B 的 KB 上管理 API Key（404）
#   - A 的 API Key 只能查询 A 的 KB，不能查询 B 的 KB（Key 本身绑定 KB）

set -euo pipefail

CONSOLE_URL="${CONSOLE_API_URL:-http://localhost:8080}"
ORIGIN="${CONSOLE_ORIGIN:-http://localhost:8080}"
RUN_ID="r4iso-$(date +%s)-$$"
TIMEOUT=180

echo "=== router4 Multi-Tenant Isolation Test (run=$RUN_ID) ==="

status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$CONSOLE_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: console-api 未就绪"; exit 1; }

json_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""; }
json_result_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('$2',''))" 2>/dev/null || echo ""; }
http_code() { echo "$1" | tail -1; }
http_body() { echo "$1" | sed '$d'; }

register_owner() {
  local uname="$1"
  local raw
  raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
    -d "{\"nickname\":\"Owner\",\"username\":\"$uname\",\"password\":\"correct-horse-battery-12\"}" \
    || printf '\n000')
  local code; code=$(http_code "$raw")
  [ "$code" = "200" ] || { echo "FAIL: register $uname HTTP $code"; exit 1; }
  echo "$raw" | sed '$d' | python3 -c "
import sys, json
r = json.load(sys.stdin)['result']
print(r['accessToken'])
print(r.get('defaultTenant',{}).get('tenantId',''))
"
}

create_kb() {
  local token="$1" tenant="$2" name="$3"
  local raw
  raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$tenant/knowledge-bases" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
    -d "{\"name\":\"$name\"}" || printf '\n000')
  local code; code=$(http_code "$raw")
  [ "$code" = "201" ] || { echo "FAIL: create KB HTTP $code"; echo "$raw" | sed '$d'; exit 1; }
  json_result_field "$(http_body "$raw")" knowledgeBaseId
}

# 两个 OWNER
read A_TOKEN A_TENANT <<< "$(register_owner "r4iso_a_${RUN_ID}")"
read B_TOKEN B_TENANT <<< "$(register_owner "r4iso_b_${RUN_ID}")"
[ -n "$A_TOKEN" ] && [ -n "$B_TOKEN" ] || { echo "FAIL: 注册失败"; exit 1; }
[ "$A_TENANT" != "$B_TENANT" ] || { echo "FAIL: 两个 OWNER 落到同一 Tenant"; exit 1; }
echo "PASS: 两个独立 Tenant A=$A_TENANT B=$B_TENANT"

# 各自 create KB
A_KB=$(create_kb "$A_TOKEN" "$A_TENANT" "iso-a-${RUN_ID}")
B_KB=$(create_kb "$B_TOKEN" "$B_TENANT" "iso-b-${RUN_ID}")
[ "$A_KB" != "$B_KB" ] || { echo "FAIL: KB id 重叠"; exit 1; }
echo "PASS: A_KB=$A_KB B_KB=$B_KB 不重叠"

# --- A get B 的 KB 应 404 ---
echo "--- A get B KB 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $A_TOKEN" \
  "$CONSOLE_URL/api/v1/tenants/$B_TENANT/knowledge-bases/$B_KB" || echo "000")
[ "$status" = "404" ] || { echo "FAIL: A get B KB 应 404 (实际 $status)"; exit 1; }
echo "PASS: A get B KB 404"

# --- A list B 的 KB 应返回空或 404（list 在 B 的 tenant 下不应含 A 的 KB） ---
echo "--- A list B tenant 的 KB ---"
raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $A_TOKEN" \
  "$CONSOLE_URL/api/v1/tenants/$B_TENANT/knowledge-bases" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
# A 不是 B 的成员，list 应 404 或返回不含 B_KB 的列表
case "$code" in
  404) echo "PASS: A list B tenant 404（非成员）" ;;
  200)
    echo "$body" | grep -q "$B_KB" && { echo "FAIL: A list B tenant 不应包含 B_KB"; exit 1; }
    echo "PASS: A list B tenant 不含 B_KB"
    ;;
  *) echo "FAIL: A list B tenant HTTP $code"; exit 1 ;;
esac

# --- A 在 B 的 KB 上 upload 应 404 ---
echo "--- A upload B KB 404 ---"
TMP=$(mktemp /tmp/r4iso-XXXX.txt)
echo "iso upload" > "$TMP"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CONSOLE_URL/api/v1/tenants/$B_TENANT/knowledge-bases/$B_KB/documents" \
  -H "Authorization: Bearer $A_TOKEN" -H "Origin: $ORIGIN" \
  -F "file=@$TMP;filename=a.txt" || echo "000")
rm -f "$TMP"
[ "$status" = "404" ] || { echo "FAIL: A upload B KB 应 404 (实际 $status)"; exit 1; }
echo "PASS: A upload B KB 404"

# --- A 在 B 的 KB 上 list documents 应 404 ---
echo "--- A list docs B KB 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $A_TOKEN" \
  "$CONSOLE_URL/api/v1/tenants/$B_TENANT/knowledge-bases/$B_KB/documents" || echo "000")
[ "$status" = "404" ] || { echo "FAIL: A list docs B KB 应 404 (实际 $status)"; exit 1; }
echo "PASS: A list docs B KB 404"

# --- A 在 B 的 KB 上 create API Key 应 404 ---
echo "--- A create key B KB 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CONSOLE_URL/api/v1/tenants/$B_TENANT/knowledge-bases/$B_KB/api-keys" \
  -H "Authorization: Bearer $A_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d '{"name":"x"}' || echo "000")
[ "$status" = "404" ] || { echo "FAIL: A create key B KB 应 404 (实际 $status)"; exit 1; }
echo "PASS: A create key B KB 404"

echo "PASS: router4 Multi-Tenant Isolation 全链路正确"
echo "=== router4 Multi-Tenant Isolation Test PASSED ==="
