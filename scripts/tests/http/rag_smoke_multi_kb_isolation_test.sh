#!/bin/bash
# router2 RAG smoke HTTP 回归 — 多知识库查询隔离（Plan 19）
# 摄取两个内容不同的 KB 后，分别按 knowledgeBaseId 查询，断言 KB-A 只召回 A 内容、KB-B 只召回 B 内容。
# 不清表、不删 volume；以 runId 隔离。
#
# 用法: bash scripts/tests/http/rag_smoke_multi_kb_isolation_test.sh

set -euo pipefail

K_URL="${K_URL:-http://localhost:8094}"
R_URL="${R_URL:-http://localhost:8083}"
RUN_ID="r-iso-$(date +%s)-$$"
TIMEOUT=180

echo "=== router2 RAG Smoke Multi-KB Isolation Test ==="
mkdir -p ./data/knowledge-files-smoke && chmod 777 ./data/knowledge-files-smoke
docker compose --profile smoke up -d --build db redis sidecar knowledge-service-smoke rag-service-smoke

wait_ready() {
  local url="$1" name="$2" status="000"
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url/actuator/health/readiness" || echo "000")
    [ "$status" = "200" ] && return 0; sleep 3
  done
  echo "FAIL: $name 未就绪"; return 1
}
wait_ready "$K_URL" knowledge || exit 1
wait_ready "$R_URL" rag || { docker compose logs --tail=40 rag-service-smoke || true; exit 1; }

TENANT=$(date +%s); WORK=$(mktemp -d /tmp/rag-iso-XXXX)
upload_doc() {
  local kb_name="$1" content="$2" out_prefix="$3" marker="$4"
  local kbResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/knowledge-bases" -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT\",\"name\":\"$kb_name-$RUN_ID\",\"createdByUserId\":\"1\"}")
  local KB_ID=$(echo "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" || true)
  [ -z "$KB_ID" ] && { echo "FAIL: 创建知识库失败: $kbResp"; exit 1; }
  local file="$WORK/$kb_name.txt"; echo "$content" > "$file"
  local SHA=$(sha256sum "$file" | awk '{print $1}'); local SIZE=$(wc -c < "$file" | tr -d ' ')
  local upResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/documents/upload" \
    -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
    -F "sha256=$SHA" -F "sizeBytes=$SIZE" -F "file=@$file;filename=doc.txt")
  local DOC_ID=$(echo "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" || true)
  [ -z "$DOC_ID" ] && { echo "FAIL: 上传失败: $upResp"; exit 1; }
  echo "$KB_ID" > "$WORK/$out_prefix.kb"; echo "$DOC_ID" > "$WORK/$out_prefix.doc"; echo "$marker" > "$WORK/$out_prefix.marker"
}
wait_ready_job() {
  local kb="$1" doc="$2" status=""
  for _ in $(seq 1 "$TIMEOUT"); do
    status=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=$kb&docId=$doc" \
      | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
    [ "$status" = "READY" ] && return 0; [ "$status" = "FAILED" ] && { echo "FAIL: job FAILED"; exit 1; }; sleep 3
  done
  echo "FAIL: job 未 READY"; exit 1
}

MARKER_A="alpha_unique_token_$RUN_ID"
MARKER_B="beta_unique_token_$RUN_ID"
upload_doc "kb-a" "alpha alpha $MARKER_A alpha 内容甲" "a" "$MARKER_A"
upload_doc "kb-b" "beta beta $MARKER_B beta 内容乙" "b" "$MARKER_B"
wait_ready_job "$(cat $WORK/a.kb)" "$(cat $WORK/a.doc)"
wait_ready_job "$(cat $WORK/b.kb)" "$(cat $WORK/b.doc)"

# 给 Dense/Sparse 索引一点时间，然后按 KB 查询证据
sleep 10
query_evidence() {
  curl -s "$R_URL/api/v1/smoke/test/retrieval/evidence?knowledgeBaseId=$1&query=$2&topN=3"
}
a_for_a=$(query_evidence "$(cat $WORK/a.kb)" "$MARKER_A" | grep -c "$MARKER_A" || true)
a_for_b=$(query_evidence "$(cat $WORK/a.kb)" "$MARKER_B" | grep -c "$MARKER_B" || true)
b_for_b=$(query_evidence "$(cat $WORK/b.kb)" "$MARKER_B" | grep -c "$MARKER_B" || true)
b_for_a=$(query_evidence "$(cat $WORK/b.kb)" "$MARKER_A" | grep -c "$MARKER_A" || true)

echo "KB-A 召回 A=$a_for_a B=$a_for_b | KB-B 召回 A=$b_for_a B=$b_for_b"
[ "$a_for_b" -eq 0 ] && [ "$b_for_a" -eq 0 ] || { echo "FAIL: 跨库串召回"; rm -rf "$WORK"; exit 1; }

echo "PASS: 两个 KB 查询互不串召回"
rm -rf "$WORK"
echo "=== router2 RAG Smoke Multi-KB Isolation Test PASSED ==="
exit 0
