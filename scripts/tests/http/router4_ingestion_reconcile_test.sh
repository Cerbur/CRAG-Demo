#!/bin/bash
# router4 摄取 Reconcile 全链路 HTTP 回归（plan_21/21.13）
#
# 范围：验证 Knowledge Reconciler 在真实 Compose 中收敛滞留 Document。
#   - 上传后，若 RAG 索引链路延迟，Document 可能停留 PENDING/PROCESSING
#   - Reconciler 由 Spring TaskScheduler 定时驱动，无需 HTTP 触发
#   - 本脚本通过上传后轮询 Document 状态，断言 Reconciler 能将滞留推进到终态
#   - 提供超时容忍：若索引链路本身够快，Reconciler 无需介入，文档直接 READY（也算通过）
#
# 注意：Reconciler 调度间隔由 knowledge-service 配置（默认较短）。本脚本只做最终状态断言，
# 不强求 Reconciler 必然介入。若文档长期停留 PENDING/PROCESSING 且 Reconciler 未收敛，
# 验收 session 检查 reconcile 调度配置与 RAG IngestionStatus RPC 链路。

set -euo pipefail

CONSOLE_URL="${CONSOLE_API_URL:-http://localhost:8080}"
ORIGIN="${CONSOLE_ORIGIN:-http://localhost:8080}"
RUN_ID="r4rec-$(date +%s)-$$"
TIMEOUT=180
CONVERGE_WAIT=180  # 等待 Reconciler 收敛的最大秒数

echo "=== router4 Ingestion Reconcile Test (run=$RUN_ID) ==="

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
USERNAME="r4rec_owner_${RUN_ID}"
raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"nickname\":\"Owner\",\"username\":\"$USERNAME\",\"password\":\"correct-horse-battery-12\"}" \
  || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "200" ] || { echo "FAIL: register HTTP $code"; exit 1; }
OWNER_TOKEN=$(json_result_field "$body" accessToken)
OWNER_TENANT=$(json_result_field "$body" defaultTenant | python3 -c "import sys,json; print(json.load(sys.stdin).get('tenantId',''))" 2>/dev/null || echo "")
[ -n "$OWNER_TOKEN" ] && [ -n "$OWNER_TENANT" ] || { echo "FAIL: 注册失败"; exit 1; }

raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases" \
  -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
  -d "{\"name\":\"rec-kb-${RUN_ID}\"}" || printf '\n000')
code=$(http_code "$raw"); body=$(http_body "$raw")
[ "$code" = "201" ] || { echo "FAIL: create KB HTTP $code"; exit 1; }
KB_ID=$(json_result_field "$body" knowledgeBaseId)

for _ in $(seq 1 30); do
  raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "200" ] || { sleep 2; continue; }
  READY=$(json_result_field "$body" apiKeyReady)
  case "$READY" in True|true) break ;; esac
  sleep 2
done

# upload 多份文档以增加 Reconciler 触发面
DOC_IDS=""
for i in 1 2 3; do
  CONTENT_FILE=$(mktemp /tmp/r4rec-doc-XXXX.txt)
  echo "router4 reconcile test $RUN_ID doc$i 内容：检索系统使用 pgvector。" > "$CONTENT_FILE"
  raw=$(curl -s -w '\n%{http_code}' -X POST "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents" \
    -H "Authorization: Bearer $OWNER_TOKEN" -H "Origin: $ORIGIN" \
    -F "file=@$CONTENT_FILE;filename=doc$i.txt" || printf '\n000')
  code=$(http_code "$raw"); body=$(http_body "$raw")
  [ "$code" = "202" ] || { echo "FAIL: upload doc$i HTTP $code"; rm -f "$CONTENT_FILE"; exit 1; }
  DOC_ID=$(json_result_field "$body" docId)
  DOC_IDS="$DOC_IDS $DOC_ID"
  rm -f "$CONTENT_FILE"
done
echo "PASS: 上传 3 份文档 ids=$DOC_IDS"

# 等待所有文档收敛到终态（READY 或 FAILED）
echo "--- 等待 Reconciler 收敛 ---"
ALL_CONVERGED=0
for _ in $(seq 1 $((CONVERGE_WAIT / 3))); do
  ALL_CONVERGED=1
  for DOC_ID in $DOC_IDS; do
    raw=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $OWNER_TOKEN" \
      "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID" || printf '\n000')
    code=$(http_code "$raw"); body=$(http_body "$raw")
    [ "$code" = "200" ] || { ALL_CONVERGED=0; break; }
    S=$(json_result_field "$body" ingestionStatus)
    case "$S" in READY|FAILED) ;; *) ALL_CONVERGED=0; break ;; esac
  done
  [ "$ALL_CONVERGED" = "1" ] && break
  sleep 3
done

if [ "$ALL_CONVERGED" != "1" ]; then
  echo "FAIL: 文档未在 ${CONVERGE_WAIT}s 内收敛（Reconciler 或索引链路异常）"
  for DOC_ID in $DOC_IDS; do
    raw=$(curl -s -H "Authorization: Bearer $OWNER_TOKEN" \
      "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID" 2>/dev/null || echo "{}")
    echo "doc=$DOC_ID status=$(json_result_field "$raw" ingestionStatus)"
  done
  exit 1
fi
echo "PASS: 全部文档收敛到终态"

# 至少一份 READY（Reconciler 或正常索引链路完成）
READY_COUNT=0
for DOC_ID in $DOC_IDS; do
  raw=$(curl -s -H "Authorization: Bearer $OWNER_TOKEN" \
    "$CONSOLE_URL/api/v1/tenants/$OWNER_TENANT/knowledge-bases/$KB_ID/documents/$DOC_ID" 2>/dev/null || echo "{}")
  S=$(json_result_field "$raw" ingestionStatus)
  [ "$S" = "READY" ] && READY_COUNT=$((READY_COUNT + 1))
done
[ "$READY_COUNT" -ge 1 ] || { echo "FAIL: 无任何文档 READY（Reconciler 未完成索引或 RAG 链路异常）"; exit 1; }
echo "PASS: $READY_COUNT 份文档 READY"

echo "PASS: router4 Ingestion Reconcile 全链路正确"
echo "=== router4 Ingestion Reconcile Test PASSED ==="
