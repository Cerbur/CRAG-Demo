#!/bin/bash
# CRAG-Demo Query Stub Success HTTP Regression (plan_7.hotfix_1)
# 验证 Stub 模式下完整 Query 链路：Knowledge 上传 → DOC_UPLOADED → RAG ingestion READY → 显式 knowledgeBaseId Query。
#
# plan_7.hotfix_1 修正：旧 /api/v1/smoke/admin/rag 只写 chunk、不创建 ingestion head/job，
# 自 plan_21.4 active-version 召回后已不可作为可召回 evidence；改走当前正式摄取状态机的 Smoke 入口。
# 本脚本启动五项依赖服务（CRAG_SERVICE_PROFILES=smoke），以唯一 RUN_ID 隔离；不清表、不删 volume。
#
# 用法: bash scripts/tests/http/query_stub_success_test.sh
#   固定端口：knowledge-service 8092、rag-service 8082（CRAG_SERVICE_PROFILES=smoke 启用 Smoke Controller）。

set -euo pipefail

K_URL="${K_URL:-http://localhost:8092}"
R_URL="${R_URL:-http://localhost:8082}"
RUN_ID="qs-$(date +%s)-$$"
VERIFICATION_CODE="verify-${RUN_ID}-abc123"  # 全局唯一、不可猜，用于锚定本次文档召回
COMPOSE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
TIMEOUT=180
CONTENT_FILE=""
FAILED=0

echo "=== Query Stub Success HTTP Regression ==="
echo "K_URL=$K_URL  R_URL=$R_URL  RUN_ID=$RUN_ID"
echo "VERIFICATION_CODE=$VERIFICATION_CODE"

cleanup() {
  [ -n "$CONTENT_FILE" ] && rm -f "$CONTENT_FILE" 2>/dev/null || true
}
trap cleanup EXIT

json_field() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2','-1'))" 2>/dev/null || echo "-1"
}
json_result() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('$2',''))" 2>/dev/null || echo ""
}

wait_ready() {
  local url="$1" name="$2" status="000"
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health/readiness" || echo "000")
    [ "$status" = "200" ] && return 0
    sleep 3
  done
  echo "FAIL: $name 未就绪 (status=$status)"
  return 1
}

# ── 0. 启动五项依赖服务（smoke Profile）──
mkdir -p ./data/knowledge-files && chmod 777 ./data/knowledge-files
cd "$COMPOSE_DIR"
export CRAG_SERVICE_PROFILES=smoke
echo "--- 启动 db/redis/sidecar/knowledge-service/rag-service ---"
docker compose up -d --build db redis sidecar knowledge-service rag-service

echo "--- 等待 readiness ---"
wait_ready "$K_URL" "knowledge-service" || { docker compose logs --tail=40 knowledge-service 2>/dev/null || true; exit 1; }
wait_ready "$R_URL" "rag-service" || { docker compose logs --tail=40 rag-service 2>/dev/null || true; exit 1; }

TENANT=$(date +%s)

# ── 1. 创建唯一知识库 ──
echo ""
echo "--- 1. 创建唯一知识库 ---"
KB_NAME="qs-kb-${RUN_ID}"
kbResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/knowledge-bases" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT\",\"name\":\"$KB_NAME\",\"createdByUserId\":\"1\"}")
KB_ID=$(printf '%s' "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" 2>/dev/null || echo "")
if [ -z "$KB_ID" ]; then
  echo "FAIL: 创建知识库失败: $kbResp"
  exit 1
fi
echo "PASS: knowledgeBaseId=$KB_ID (name=$KB_NAME)"

# ── 2. 上传含 VERIFICATION_CODE 的临时 .txt ──
echo ""
echo "--- 2. 上传唯一 .txt（含 VERIFICATION_CODE）---"
CONTENT_FILE=$(mktemp /tmp/qs-doc-XXXX.txt)
cat > "$CONTENT_FILE" <<EOF
${VERIFICATION_CODE} CRAG-Demo 是一个基于 RAG 的问答机器人，使用 PostgreSQL 数据库和 pgvector 向量扩展进行混合检索。本段内容仅用于 Query Stub success 回归。
EOF
SHA=$(sha256sum "$CONTENT_FILE" | awk '{print $1}')
SIZE=$(wc -c < "$CONTENT_FILE" | tr -d ' ')
upResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/documents/upload" \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$SHA" -F "sizeBytes=$SIZE" -F "file=@$CONTENT_FILE;filename=doc.txt")
DOC_ID=$(printf '%s' "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" 2>/dev/null || echo "")
INGEST=$(printf '%s' "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['ingestionStatus'])" 2>/dev/null || echo "")
if [ -z "$DOC_ID" ] || [ "$INGEST" != "PENDING" ]; then
  echo "FAIL: 上传失败 (docId=$DOC_ID status=$INGEST): $upResp"
  exit 1
fi
echo "PASS: docId=$DOC_ID ingestionStatus=$INGEST"

# ── 3. 轮询 RAG ingestion job 到 READY ──
echo ""
echo "--- 3. 等待 RAG ingestion job READY ---"
FINAL_STATUS=""
for _ in $(seq 1 $((TIMEOUT / 3))); do
  jobResp=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=$KB_ID&docId=$DOC_ID")
  FINAL_STATUS=$(printf '%s' "$jobResp" | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
  [ "$FINAL_STATUS" = "READY" ] && break
  if [ "$FINAL_STATUS" = "FAILED" ]; then
    echo "FAIL: ingestion job FAILED (kb=$KB_ID doc=$DOC_ID)"
    docker compose logs --tail=60 rag-service knowledge-service 2>/dev/null || true
    exit 1
  fi
  sleep 3
done
if [ "$FINAL_STATUS" != "READY" ]; then
  echo "FAIL: ingestion 未在超时内 READY (last=$FINAL_STATUS)"
  exit 1
fi
echo "PASS: ingestion READY (kb=$KB_ID doc=$DOC_ID)"

# ── 4. 显式 knowledgeBaseId Query ──
echo ""
echo "--- 4. 显式 knowledgeBaseId Query ---"
QUERY_RESP=$(curl -s -X POST "$R_URL/api/v1/smoke/query" \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"${VERIFICATION_CODE} 使用什么数据库？\",\"knowledgeBaseId\":${KB_ID}}" || echo '{"code":-1}')
QUERY_CODE=$(json_field "$QUERY_RESP" code)
if [ "$QUERY_CODE" = "0" ]; then
  echo "PASS: Query code=0"
else
  echo "FAIL: Query code=$QUERY_CODE (expected 0), resp=$QUERY_RESP"
  FAILED=1
fi

# ── 5. 断言固定 Stub answer ──
STUB_ANSWER=$(json_result "$QUERY_RESP" answer)
if [ "$STUB_ANSWER" = "已根据知识库证据生成回答。[S1]" ]; then
  echo "PASS: Answer matches fixed Stub answer"
else
  echo "FAIL: Answer is '$STUB_ANSWER' (expected '已根据知识库证据生成回答。[S1]')"
  FAILED=1
fi

# ── 6. 断言 sources 非空且属于本次 KB 文档 ──
# 该 KB 仅含本次上传的唯一文档（含全局唯一 VERIFICATION_CODE），question 也含该 code，
# 故非空 sources 即证明召回本次文档；同时校验 source 结构与原 success 回归口径一致。
echo ""
echo "--- 5. 校验 sources 命中本次文档 ---"
SOURCE_INFO=$(printf '%s' "$QUERY_RESP" | python3 -c "
import sys, json, re
resp = json.load(sys.stdin)
sources = resp.get('result', {}).get('sources', [])
count = len(sources)
ref = pid = ''
ref_ok = pid_decimal = children_nonempty = False
if sources:
    s = sources[0]
    ref = s.get('reference', '')
    pid = s.get('parentChunkId', '')
    children = s.get('matchedChildIds', [])
    ref_ok = bool(re.match(r'^S\d+$', ref))
    pid_decimal = bool(re.match(r'^[0-9]+$', str(pid)))
    children_nonempty = isinstance(children, list) and len(children) > 0
print(f'{count}|{ref}|{pid}|{ref_ok}|{pid_decimal}|{children_nonempty}')
" 2>/dev/null || echo "0||||False|False")
SRC_COUNT=$(printf '%s' "$SOURCE_INFO" | cut -d'|' -f1)
SRC_REF=$(printf '%s' "$SOURCE_INFO" | cut -d'|' -f2)
SRC_PID=$(printf '%s' "$SOURCE_INFO" | cut -d'|' -f3)
SRC_REF_OK=$(printf '%s' "$SOURCE_INFO" | cut -d'|' -f4)
SRC_PID_OK=$(printf '%s' "$SOURCE_INFO" | cut -d'|' -f5)
SRC_CHILD_OK=$(printf '%s' "$SOURCE_INFO" | cut -d'|' -f6)

if [ "$SRC_COUNT" -gt 0 ] 2>/dev/null; then
  echo "PASS: sources 非空 ($SRC_COUNT item(s)) → 命中本次 KB 文档（KB 仅含唯一 VERIFICATION_CODE 文档）"
else
  echo "FAIL: sources 为空（未召回本次文档）"
  FAILED=1
fi
echo "  source[0]: reference=$SRC_REF parentChunkId=$SRC_PID"
if [ "$SRC_REF_OK" = "True" ]; then
  echo "PASS: reference 格式 S<number>"
else
  echo "FAIL: reference 格式异常 ($SRC_REF)"
  FAILED=1
fi
if [ "$SRC_PID_OK" = "True" ]; then
  echo "PASS: parentChunkId 为十进制字符串"
else
  echo "FAIL: parentChunkId 非十进制 ($SRC_PID)"
  FAILED=1
fi
if [ "$SRC_CHILD_OK" = "True" ]; then
  echo "PASS: matchedChildIds 非空"
else
  echo "FAIL: matchedChildIds 为空"
  FAILED=1
fi

# ── Final ──
echo ""
echo "=== Test data preserved with RUN_ID=$RUN_ID (KB=$KB_ID DOC=$DOC_ID) ==="
if [ "$FAILED" -eq 0 ]; then
  echo "=== Query Stub Success HTTP Regression PASSED ==="
  exit 0
else
  echo "=== Query Stub Success HTTP Regression FAILED ==="
  exit 1
fi
