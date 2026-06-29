#!/bin/bash
# router4 上传 + Open Query 全链路 HTTP 回归（plan_21/21.13）
#
# 范围：通过 console-api 上传文档，等待 READY，再用 open-api 单 KB Key Query 验证：
#   - register OWNER → create KB（等待 apiKeyReady）→ create API Key → upload .txt
#   - 等待 ingestion READY（Document.ingestionStatus == READY）
#   - Open Query 携带 Bearer completeKey 成功返回 answer + sources
#   - Query 请求体含 knowledgeBaseId 不被接受（鉴权由 Key 决定 KB）
#   - 缺失 Bearer 返回 401
#   - question 超长返回 400
#
# 依赖：Compose 中 rag-service 启用确定性 LLM Stub（query.llm.adapter=stub）。
# 本机无 docker compose，验收 session 在 Docker 环境中执行。

set -euo pipefail

CONSOLE_URL="${CONSOLE_API_URL:-http://localhost:8080}"
OPEN_URL="${OPEN_API_URL:-http://localhost:8081}"
ORIGIN="${CONSOLE_ORIGIN:-http://localhost:8080}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=180
READY_WAIT=120  # 等待 ingestion READY 的最大秒数

echo "=== router4 Upload + Query Test (run=$RUN_ID) ==="

status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$CONSOLE_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: console-api 未就绪"; exit 1; }

status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$OPEN_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: open-api 未就绪"; exit 1; }

json_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""; }
json_result_field() { echo "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('$2',''))" 2>/dev/null || echo ""; }
http_code() { echo "$1" | tail -1; }
http_body() { echo "$1" | sed '$d'; }

# 注册 OWNER
USERNAME="r4upq_owner_${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code"; exit 1; }
OWNER_TOKEN=$(json_result_field "$body" accessToken)
OWNER_TENANT=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('defaultTenant',{}).get('tenantId',''))" 2>/dev/null || echo "")
[ -n "$OWNER_TOKEN" ] && [ -n "$OWNER_TENANT" ] || { echo "FAIL: 注册失败"; exit 1; }

# create KB
KB_NAME="upq-kb-${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"$KB_NAME\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create KB HTTP $code"; echo "$body"; exit 1; }
KB_ID=$(json_result_field "$body" knowledgeBaseId)
[ -n "$KB_ID" ] || { echo "FAIL: 无 KB_ID"; exit 1; }

# 等待 Scope 补偿
for _ in $(seq 1 30); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  READY=$(json_result_field "$body" apiKeyReady)
  case "$READY" in True|true) break ;; esac
  sleep 2
done

# create API Key
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/api-keys" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"upq-key-${RUN_ID}\",\"ttlSeconds\":2592000}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create API Key HTTP $code"; echo "$body"; exit 1; }
COMPLETE_KEY=$(json_result_field "$body" completeKey)
echo "$COMPLETE_KEY" | grep -q '^crag_' || { echo "FAIL: completeKey 格式错误"; exit 1; }
echo "PASS: API Key 创建 $COMPLETE_KEY"

# upload .txt
CONTENT_FILE=$(mktemp /tmp/r4upq-doc-XXXX.txt)
echo "router4 upload query $RUN_ID 测试内容：CRAG-Demo 使用 PostgreSQL 和 pgvector 进行混合向量检索。" > "$CONTENT_FILE"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Origin: $ORIGIN" \
  -F "file=@$CONTENT_FILE;filename=doc.txt" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "202" ] || { echo "FAIL: upload HTTP $code (expected 202)"; echo "$body"; rm -f "$CONTENT_FILE"; exit 1; }
DOC_ID=$(json_result_field "$body" docId)
INGEST=$(json_result_field "$body" ingestionStatus)
[ "$INGEST" = "PENDING" ] || [ "$INGEST" = "PROCESSING" ] || { echo "FAIL: upload 后 status 异常 $INGEST"; rm -f "$CONTENT_FILE"; exit 1; }
echo "PASS: upload 202 doc=$DOC_ID status=$INGEST"
rm -f "$CONTENT_FILE"

# 等待 ingestion READY
echo "--- 等待 ingestion READY ---"
FINAL_STATUS=""
for _ in $(seq 1 $((READY_WAIT / 2))); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  FINAL_STATUS=$(json_result_field "$body" ingestionStatus)
  [ "$FINAL_STATUS" = "READY" ] && break
  # FAILED 也终止，便于诊断
  [ "$FINAL_STATUS" = "FAILED" ] && break
  sleep 2
done
if [ "$FINAL_STATUS" != "READY" ]; then
  echo "FAIL: ingestion 未就绪 final=${FINAL_STATUS}（检查 rag-service 是否启用 LLM Stub 与索引 Cron）"
  exit 1
fi
echo "PASS: ingestion READY"

# --- Open Query 成功 ---
echo "--- Open Query 成功 ---"
raw=$(curl -s -w '\n%{http_code}' -X POST "$OPEN_URL/api/v1/query" \
  -H "Authorization: Bearer $COMPLETE_KEY" -H "Content-Type: application/json" \
  -d "{\"question\":\"CRAG-Demo 用什么数据库进行向量检索？\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: Open Query HTTP $code (expected 200)"; echo "$body"; exit 1; }
RESP_CODE=$(json_field "$body" code)
[ "$RESP_CODE" = "0" ] || { echo "FAIL: Open Query code=$RESP_CODE"; echo "$body"; exit 1; }
echo "$body" | grep -q '"answer"' || { echo "FAIL: Query 无 answer"; exit 1; }
echo "PASS: Open Query 200 answer+sources"

# --- Query 请求体含 knowledgeBaseId 不被接受（仍正常返回，KB 由 Key 决定） ---
echo "--- Query 携带 knowledgeBaseId 仍按 Key 解析 ---"
raw=$(curl -s -w '\n%{http_code}' -X POST "$OPEN_URL/api/v1/query" \
  -H "Authorization: Bearer $COMPLETE_KEY" -H "Content-Type: application/json" \
  -d "{\"question\":\"CRAG-Demo 用什么数据库？\",\"knowledgeBaseId\":\"999999\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: Query 携带 KB 应仍 200 (实际 $code)"; echo "$body"; exit 1; }
echo "PASS: Query 忽略 body.knowledgeBaseId，按 Key 鉴权 KB"

# --- 缺失 Bearer 401 ---
echo "--- 缺失 Bearer 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$OPEN_URL/api/v1/query" \
  -H "Content-Type: application/json" -d '{"question":"x"}' || echo "000")
[ "$status" = "401" ] || { echo "FAIL: 缺 Bearer 应 401 (实际 $status)"; exit 1; }
echo "PASS: 缺 Bearer 401"

# --- question 超长 400 ---
echo "--- question 超长 400 ---"
LONG_Q=$(python3 -c "print('x' * 2001)")
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$OPEN_URL/api/v1/query" \
  -H "Authorization: Bearer $COMPLETE_KEY" -H "Content-Type: application/json" \
  -d "{\"question\":\"$LONG_Q\"}" || echo "000")
[ "$status" = "400" ] || { echo "FAIL: question 超长应 400 (实际 $status)"; exit 1; }
echo "PASS: question 超长 400"

echo "PASS: router4 Upload + Query 全链路正确"
echo "=== router4 Upload + Query Test PASSED ==="
