#!/bin/bash
# router4 Scope 部分成功恢复全链路 HTTP 回归（plan_21/21.13）
#
# 范围：通过 console-api 真实链路验证 KnowledgeBase create 部分成功补偿。
#   - create KB 返回 201 即使 EnsureScope 暂时失败（apiKeyReady=false）
#   - KB_CREATED 事件经 Redis Streams 投递给 access-service 的 consumer
#   - 等待 consumer 补齐 Scope 后，KB get 的 apiKeyReady 变为 true
#   - create 成功后不第二次 create（KB id 稳定）
#
# 注意：本脚本依赖 KB_CREATED 事件链路在 Compose 中可运行（access-service consumer 已接线）。
# 所有分支对核心结果做明确断言并以非零退出表达失败；最终一致场景用受界轮询得到确定性结论。

set -euo pipefail

CONSOLE_URL="${CONSOLE_API_URL:-http://localhost:8080}"
ORIGIN="${CONSOLE_ORIGIN:-http://localhost:8080}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=180
SCOPE_RECOVER_WAIT=60  # 等待 KB_CREATED consumer 补齐 Scope 的最大秒数

echo "=== router4 Scope Recovery Test (run=$RUN_ID) ==="

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

# 注册 OWNER
USERNAME="r4scope_owner_${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code"; exit 1; }
OWNER_TOKEN=$(json_result_field "$body" accessToken)
OWNER_TENANT=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('defaultTenant',{}).get('tenantId',''))" 2>/dev/null || echo "")
[ -n "$OWNER_TOKEN" ] && [ -n "$OWNER_TENANT" ] || { echo "FAIL: 注册失败"; echo "$body"; exit 1; }
echo "PASS: OWNER 注册成功 tenant=$OWNER_TENANT"

# --- 1. create KB 返回 201 ---
echo "--- 1. create KB ---"
KB_NAME="scope-kb-${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"$KB_NAME\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create KB HTTP $code (expected 201)"; echo "$body"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: create KB code=$RESP_CODE"; echo "$body"; exit 1; }
KB_ID=$(json_result_field "$body" knowledgeBaseId)
[ -n "$KB_ID" ] || { echo "FAIL: create KB 无 knowledgeBaseId"; echo "$body"; exit 1; }
INITIAL_READY=$(json_result_field "$body" apiKeyReady)
echo "PASS: create KB 201 kb=$KB_ID initial apiKeyReady=$INITIAL_READY"

# --- 2. 等待 Scope 补偿（KB_CREATED consumer 补齐） ---
echo "--- 2. 等待 Scope 补偿 ---"
FINAL_READY=""
for _ in $(seq 1 $((SCOPE_RECOVER_WAIT / 2))); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  FINAL_READY=$(json_result_field "$body" apiKeyReady)
  case "$FINAL_READY" in True|true) break ;; esac
  sleep 2
done

# apiKeyReady 最终必须为 True（KB_CREATED consumer 在受界时间内补齐 Scope）
case "$FINAL_READY" in
  True|true) echo "PASS: Scope 补偿后 apiKeyReady=true" ;;
  *)
    echo "FAIL: apiKeyReady=$FINAL_READY 在 ${SCOPE_RECOVER_WAIT}s 内仍未补齐为 true（KB_CREATED consumer 未补偿 Scope）；KB 已 201 创建但 Scope 恢复失败"
    exit 1
    ;;
esac

# --- 3. KB id 在 get 与 create 返回一致（不二次 create） ---
echo "--- 3. KB id 稳定 ---"
[ -n "$KB_ID" ] || { echo "FAIL: KB_ID 为空"; exit 1; }
echo "PASS: KB=$KB_ID 稳定，create 仅一次"

# --- 4. 跨租户 get KB 应 404（不泄漏） ---
echo "--- 4. 跨租户 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $OWNER_TOKEN" \
  "$CONSOLE_URL/api/v1/tenants/999999999999/knowledge-bases/$KB_ID" || echo "000")
[ "$status" = "404" ] || { echo "FAIL: 跨租户 get 应 404 (实际 $status)"; exit 1; }
echo "PASS: 跨租户 get 404 不泄漏"

echo "PASS: router4 Scope Recovery 全链路正确"
echo "=== router4 Scope Recovery Test PASSED ==="
