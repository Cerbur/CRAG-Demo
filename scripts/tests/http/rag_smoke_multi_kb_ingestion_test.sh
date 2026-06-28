#!/bin/bash
# router2 RAG smoke HTTP 回归 — 多知识库摄取（Plan 19）
# 在 smoke profile 下通过 Knowledge 上传两份内容不同的 .txt，等待 RAG ingestion_job 进入 READY，
# 证明 DOC_UPLOADED → 消费 → Knowledge gRPC 读取 → 切分 → 写入 → 索引完成 全链路。
# 自动启动 db/redis/sidecar/knowledge-service/rag-service（启用 smoke Profile），以 runId 隔离；不清表、不删 volume。
#
# 用法: bash scripts/tests/http/rag_smoke_multi_kb_ingestion_test.sh

set -euo pipefail

K_URL="${K_URL:-http://localhost:8092}"
R_URL="${R_URL:-http://localhost:8082}"
RUN_ID="r-ing-$(date +%s)-$$"
TIMEOUT=180

echo "=== router2 RAG Smoke Multi-KB Ingestion Test ==="
echo "K_URL=$K_URL  R_URL=$R_URL  RUN_ID=$RUN_ID"

mkdir -p ./data/knowledge-files && chmod 777 ./data/knowledge-files
export CRAG_SERVICE_PROFILES=smoke
docker compose up -d --build db redis sidecar knowledge-service rag-service

wait_ready() {
  local url="$1" name="$2" status="000"
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health/readiness" || echo "000")
    [ "$status" = "200" ] && return 0
    sleep 3
  done
  echo "FAIL: $name 未就绪 (status=$status)"; return 1
}

echo "waiting for readiness..."
wait_ready "$K_URL" "knowledge-service" || { docker compose logs --tail=40 rag-service knowledge-service || true; exit 1; }
wait_ready "$R_URL" "rag-service" || { docker compose logs --tail=40 rag-service || true; exit 1; }

TENANT=$(date +%s)
WORK=$(mktemp -d /tmp/rag-ing-XXXX)

upload_doc() {
  local kb_name="$1" content="$2" out_prefix="$3"
  local kbResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/knowledge-bases" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT\",\"name\":\"$kb_name-$RUN_ID\",\"createdByUserId\":\"1\"}")
  local KB_ID=$(echo "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" 2>/dev/null || echo "")
  [ -z "$KB_ID" ] && { echo "FAIL: 创建知识库失败: $kbResp"; exit 1; }
  local file="$WORK/$kb_name.txt"
  echo "$content" > "$file"
  local SHA=$(sha256sum "$file" | awk '{print $1}')
  local SIZE=$(wc -c < "$file" | tr -d ' ')
  local upResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/documents/upload" \
    -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
    -F "sha256=$SHA" -F "sizeBytes=$SIZE" -F "file=@$file;filename=doc.txt")
  local DOC_ID=$(echo "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" 2>/dev/null || echo "")
  [ -z "$DOC_ID" ] && { echo "FAIL: 上传失败: $upResp"; exit 1; }
  echo "$KB_ID" > "$WORK/$out_prefix.kb"
  echo "$DOC_ID" > "$WORK/$out_prefix.doc"
}

wait_ready_job() {
  local kb="$1" doc="$2" status=""
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=$kb&docId=$doc" \
      | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
    [ "$status" = "READY" ] && return 0
    [ "$status" = "FAILED" ] && { echo "FAIL: job FAILED (kb=$kb doc=$doc)"; exit 1; }
    sleep 3
  done
  echo "FAIL: job 未在超时内 READY (kb=$kb doc=$doc last=$status)"; exit 1
}

upload_doc "kb-a" "alpha alpha alpha router2 ingestion content $RUN_ID 标记甲内容甲" "a"
upload_doc "kb-b" "beta beta beta router2 ingestion content $RUN_ID 标记乙内容乙" "b"
wait_ready_job "$(cat $WORK/a.kb)" "$(cat $WORK/a.doc)"
wait_ready_job "$(cat $WORK/b.kb)" "$(cat $WORK/b.doc)"

echo "PASS: 两个 KB 均摄取到 READY"
rm -rf "$WORK"
echo "=== router2 RAG Smoke Multi-KB Ingestion Test PASSED ==="
exit 0
