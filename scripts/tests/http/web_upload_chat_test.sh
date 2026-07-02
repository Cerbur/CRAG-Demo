#!/bin/bash
# Web Console 上传 + Chat 同源代理 HTTP 回归（plan_22/22.9）
#
# 范围：完整核心流程只经浏览器入口 http://localhost:3000：
#   - register OWNER（经 /console-api 代理）→ create KB → 等 apiKeyReady → create API Key
#   - upload .txt（经代理 multipart）→ 等 ingestion READY
#   - Open Query（经 /open-api 代理，Bearer completeKey）→ answer + sources
#   - Query 请求体含 knowledgeBaseId 仍 200（KB 由 Key 决定）
#   - 缺 Bearer 401；question 超长 400
#
# 用法: bash scripts/tests/http/web_upload_chat_test.sh
# 前置: 完整 Compose 已启动（含 web；rag-service 启用确定性 LLM Stub，CRAG_QUERY_LLM_PROVIDER=stub）。
#       本脚本不执行 compose up。数据隔离：唯一 RUN_ID，不清数据库/Volume。

set -euo pipefail

WEB_URL="${WEB_URL:-http://localhost:3000}"
ORIGIN="${WEB_ORIGIN:-http://localhost:3000}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=180
READY_WAIT=120

echo "=== Web Upload + Chat Proxy Test (run=$RUN_ID) ==="

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

# register OWNER（经代理）。
USERNAME="webchat_owner_${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$WEB_URL/console-api/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code"; echo "$body"; exit 1; }
OWNER_TOKEN=$(json_result_field "$body" accessToken)
OWNER_TENANT=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('defaultTenant',{}).get('tenantId',''))" 2>/dev/null || echo "")
[ -n "$OWNER_TOKEN" ] && [ -n "$OWNER_TENANT" ] || { echo "FAIL: 注册失败"; exit 1; }

# create KB（经代理）。
KB_NAME="webchat-kb-${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$WEB_URL/console-api/api/v1/tenants/$OWNER_TENANT/knowledge-bases" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"$KB_NAME\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create KB HTTP $code"; echo "$body"; exit 1; }
KB_ID=$(json_result_field "$body" knowledgeBaseId)
[ -n "$KB_ID" ] || { echo "FAIL: 无 KB_ID"; exit 1; }

# 等待 Scope 补偿使 apiKeyReady=true。
for _ in $(seq 1 30); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$WEB_URL/console-api/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  READY=$(json_result_field "$body" apiKeyReady)
  case "$READY" in True|true) break ;; esac
  sleep 2
done

# create API Key（经代理）。
raw=$(curl -s -w '\n%{http_code}' -X POST "$WEB_URL/console-api/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/api-keys" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"webchat-key-${RUN_ID}\",\"ttlSeconds\":2592000}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create API Key HTTP $code"; echo "$body"; exit 1; }
COMPLETE_KEY=$(json_result_field "$body" completeKey)
echo "$COMPLETE_KEY" | grep -q '^crag_' || { echo "FAIL: completeKey 格式错误"; exit 1; }
echo "PASS: 经代理创建 API Key"

# upload .txt（经代理 multipart）。
CONTENT_FILE=$(mktemp /tmp/webchat-doc-XXXX.txt)
echo "web upload chat $RUN_ID 测试内容：CRAG-Demo 通过同源代理上传文档并完成混合检索。" > "$CONTENT_FILE"
raw=$(curl -s -w '\n%{http_code}' -X POST "$WEB_URL/console-api/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Origin: $ORIGIN" \
  -F "file=@$CONTENT_FILE;filename=doc.txt" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "202" ] || { echo "FAIL: upload HTTP $code (expected 202)"; echo "$body"; rm -f "$CONTENT_FILE"; exit 1; }
DOC_ID=$(json_result_field "$body" docId)
echo "PASS: 经代理上传 202 doc=$DOC_ID"
rm -f "$CONTENT_FILE"

# 等待 ingestion READY。
echo "--- 等待 ingestion READY ---"
FINAL_STATUS=""
for _ in $(seq 1 $((READY_WAIT / 2))); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$WEB_URL/console-api/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  FINAL_STATUS=$(json_result_field "$body" ingestionStatus)
  [ "$FINAL_STATUS" = "READY" ] && break
  [ "$FINAL_STATUS" = "FAILED" ] && break
  sleep 2
done
if [ "$FINAL_STATUS" != "READY" ]; then
  echo "FAIL: ingestion 未就绪 final=${FINAL_STATUS}（检查 rag-service LLM Stub 与索引链路）"
  exit 1
fi
echo "PASS: ingestion READY"

# Open Query（经 /open-api 代理）。
echo "--- Open Query 经 /open-api 代理成功 ---"
raw=$(curl -s -w '\n%{http_code}' -X POST "$WEB_URL/open-api/api/v1/query" \
  -H "Authorization: Bearer $COMPLETE_KEY" -H "Content-Type: application/json" \
  -d '{"question":"CRAG-Demo 用什么数据库进行向量检索？"}' || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: Open Query HTTP $code"; echo "$body"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: Open Query code=$RESP_CODE"; echo "$body"; exit 1; }
echo "$body" | grep -q '"answer"' || { echo "FAIL: Query 无 answer"; exit 1; }
echo "PASS: Open Query 经代理 200 answer"

# Query 携带 knowledgeBaseId 仍 200（KB 由 Key 决定）。
echo "--- Query 携带 knowledgeBaseId 仍按 Key 解析 ---"
raw=$(curl -s -w '\n%{http_code}' -X POST "$WEB_URL/open-api/api/v1/query" \
  -H "Authorization: Bearer $COMPLETE_KEY" -H "Content-Type: application/json" \
  -d '{"question":"CRAG-Demo 用什么数据库？","knowledgeBaseId":"999999"}' || printf '\n000')
code=$(http_code "$raw")
[ "$code" = "200" ] || { echo "FAIL: Query 携带 KB 应仍 200 实际 $code"; exit 1; }
echo "PASS: Query 忽略 body.knowledgeBaseId"

# 缺 Bearer 401。
echo "--- 缺 Bearer 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$WEB_URL/open-api/api/v1/query" \
  -H "Content-Type: application/json" -d '{"question":"x"}' || echo "000")
[ "$status" = "401" ] || { echo "FAIL: 缺 Bearer 应 401 实际 $status"; exit 1; }
echo "PASS: 缺 Bearer 401"

# question 超长 400。
echo "--- question 超长 400 ---"
LONG_Q=$(python3 -c "print('x' * 2001)")
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$WEB_URL/open-api/api/v1/query" \
  -H "Authorization: Bearer $COMPLETE_KEY" -H "Content-Type: application/json" \
  -d "{\"question\":\"$LONG_Q\"}" || echo "000")
[ "$status" = "400" ] || { echo "FAIL: question 超长应 400 实际 $status"; exit 1; }
echo "PASS: question 超长 400"

echo "PASS: Web 上传 + Chat 同源代理全链路正确"
echo "=== Web Upload + Chat Proxy Test PASSED ==="
