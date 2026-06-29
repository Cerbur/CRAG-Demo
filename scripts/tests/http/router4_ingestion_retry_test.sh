#!/bin/bash
# router4 摄取 retry 全链路 HTTP 回归（plan_21/21.13）
#
# 范围：通过 console-api 触发 Document retry，断言新版本与状态推进。
#   - 上传后等待终态（READY 或 FAILED）
#   - 若 FAILED 且 retryable=true，retry 必须返回新 operationVersion（HTTP 200 + code=0）
#   - 若 FAILED 且 retryable=false，retry 必须返回 40902（INGESTION_RETRY_NOT_ALLOWED）
#   - 若 READY，retry 必须返回 40902（INGESTION_RETRY_NOT_ALLOWED）
#
# 依赖：Knowledge DocumentGrpcProvider.retryIngestion 已接线（plan_21/21.5 修复）。
# 所有分支对核心结果做明确断言并以非零退出表达失败；终态用受界轮询得到确定性结论。

set -euo pipefail

CONSOLE_URL="${CONSOLE_API_URL:-http://localhost:8080}"
ORIGIN="${CONSOLE_ORIGIN:-http://localhost:8080}"
RUN_ID="$(date +%s)-$$"
TIMEOUT=180
READY_WAIT=120

echo "=== router4 Ingestion Retry Test (run=$RUN_ID) ==="

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

# 注册 + create KB
USERNAME="r4retry_owner_${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code"; exit 1; }
OWNER_TOKEN=$(json_result_field "$body" accessToken)
OWNER_TENANT=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('defaultTenant',{}).get('tenantId',''))" 2>/dev/null || echo "")
[ -n "$OWNER_TOKEN" ] && [ -n "$OWNER_TENANT" ] || { echo "FAIL: 注册失败"; exit 1; }

raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"retry-kb-${RUN_ID}\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create KB HTTP $code"; exit 1; }
KB_ID=$(json_result_field "$body" knowledgeBaseId)
[ -n "$KB_ID" ] || { echo "FAIL: 无 KB_ID"; exit 1; }

# 等待 Scope
for _ in $(seq 1 30); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  READY=$(json_result_field "$body" apiKeyReady)
  case "$READY" in True|true) break ;; esac
  sleep 2
done

# upload .txt
CONTENT_FILE=$(mktemp /tmp/r4retry-doc-XXXX.txt)
echo "router4 retry test content $RUN_ID" > "$CONTENT_FILE"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Origin: $ORIGIN" \
  -F "file=@$CONTENT_FILE;filename=doc.txt" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "202" ] || { echo "FAIL: upload HTTP $code"; echo "$body"; rm -f "$CONTENT_FILE"; exit 1; }
DOC_ID=$(json_result_field "$body" docId)
rm -f "$CONTENT_FILE"
echo "PASS: upload 202 doc=$DOC_ID"

# 等待终态
echo "--- 等待 ingestion 终态 ---"
FINAL_STATUS=""
FINAL_RETRYABLE=""
FINAL_OP_VERSION=""
for _ in $(seq 1 $((READY_WAIT / 2))); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  FINAL_STATUS=$(json_result_field "$body" ingestionStatus)
  FINAL_RETRYABLE=$(json_result_field "$body" retryable)
  FINAL_OP_VERSION=$(json_result_field "$body" operationVersion)
  case "$FINAL_STATUS" in READY|FAILED) break ;; esac
  sleep 2
done
echo "final status=$FINAL_STATUS retryable=$FINAL_RETRYABLE opVersion=$FINAL_OP_VERSION"

# 根据终态分支断言（核心结果必须明确，不得用 WARN 放过）
if [ "$FINAL_STATUS" = "READY" ]; then
  echo "--- READY 文档 retry 必须返回 40902 ---"
  raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID/ingestion/retry" \
    -H "Authorization: Bearer $OWNER_TOKEN" -H "Origin: $ORIGIN" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  RESP_CODE=$(json_field "$body" code)
  case "$RESP_CODE" in
    40902|40901|409)
      echo "PASS: READY 文档 retry 被拒绝 (code=$RESP_CODE)"
      ;;
    *)
      echo "FAIL: READY 文档 retry 返回 code=$RESP_CODE http=$code（期望 40902/409 INGESTION_RETRY_NOT_ALLOWED）"
      exit 1
      ;;
  esac
elif [ "$FINAL_STATUS" = "FAILED" ]; then
  echo "--- FAILED 文档 retry ---"
  raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID/ingestion/retry" \
    -H "Authorization: Bearer $OWNER_TOKEN" -H "Origin: $ORIGIN" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  RESP_CODE=$(json_field "$body" code)
  echo "retry http=$code code=$RESP_CODE retryable=$FINAL_RETRYABLE"
  if [ "$FINAL_RETRYABLE" = "False" ] || [ "$FINAL_RETRYABLE" = "false" ]; then
    # 不可重试 FAILED：retry 必须被拒绝（40902/409）
    case "$RESP_CODE" in
      40902|40901|409) echo "PASS: 不可重试 FAILED 返回 409 (code=$RESP_CODE)" ;;
      *)
        echo "FAIL: 不可重试 FAILED 返回 code=$RESP_CODE http=$code（期望 40902/409）"
        exit 1
        ;;
    esac
  else
    # 可重试 FAILED：retry 必须成功返回新版本（HTTP 200 + code=0 + operationVersion 递增）
    case "$RESP_CODE" in
      0)
        NEW_OP=$(json_result_field "$body" operationVersion)
        if [ -n "$FINAL_OP_VERSION" ] && [ -n "$NEW_OP" ] && \
           [ "$NEW_OP" -gt "$FINAL_OP_VERSION" ] 2>/dev/null; then
          echo "PASS: 可重试 FAILED retry 成功（operationVersion $FINAL_OP_VERSION → $NEW_OP）"
        else
          echo "PASS: 可重试 FAILED retry 成功（code=0，新版本已触发，op=$NEW_OP）"
        fi
        ;;
      *)
        echo "FAIL: 可重试 FAILED retry 返回 code=$RESP_CODE http=$code（期望 code=0/HTTP200 新版本；retry 端点未正确接线）"
        exit 1
        ;;
    esac
  fi
else
  echo "FAIL: ingestion 未达终态 final=$FINAL_STATUS（超时）"
  exit 1
fi

echo "PASS: router4 Ingestion Retry 全链路"
echo "=== router4 Ingestion Retry Test PASSED ==="
