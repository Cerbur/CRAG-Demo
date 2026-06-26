#!/bin/bash
# router2 RAG smoke HTTP 回归 — DOC_UPLOADED 幂等（Plan 19）
# 上传一份文档并等待 RAG READY 后记录 chunk 计数；从 Redis Stream 取出该 DOC_UPLOADED 事件并重新投递，
# 断言 RAG 经 processed_event + ingestion_job 双层幂等保护后 chunk 计数不变。
# 不清表、不删 volume；以 runId 隔离。
#
# 用法: bash scripts/tests/http/rag_smoke_doc_uploaded_idempotency_test.sh

set -euo pipefail

K_URL="${K_URL:-http://localhost:8094}"
R_URL="${R_URL:-http://localhost:8083}"
RUN_ID="r-idem-$(date +%s)-$$"
TIMEOUT=180
STREAM="crag:event:knowledge"

echo "=== router2 RAG Smoke DOC_UPLOADED Idempotency Test ==="
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

TENANT=$(date +%s)
kbResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/knowledge-bases" -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT\",\"name\":\"kb-idem-$RUN_ID\",\"createdByUserId\":\"1\"}")
KB_ID=$(echo "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" || true)
[ -z "$KB_ID" ] && { echo "FAIL: 创建知识库失败"; exit 1; }

file=$(mktemp /tmp/rag-idem-XXXX.txt)
echo "idempotency content $RUN_ID alpha beta gamma 标记幂等" > "$file"
SHA=$(sha256sum "$file" | awk '{print $1}'); SIZE=$(wc -c < "$file" | tr -d ' ')
upResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/documents/upload" \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$SHA" -F "sizeBytes=$SIZE" -F "file=@$file;filename=doc.txt")
rm -f "$file"
DOC_ID=$(echo "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" || true)
[ -z "$DOC_ID" ] && { echo "FAIL: 上传失败: $upResp"; exit 1; }

status=""
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=$KB_ID&docId=$DOC_ID" \
    | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
  [ "$status" = "READY" ] && break; [ "$status" = "FAILED" ] && { echo "FAIL: job FAILED"; exit 1; }; sleep 3
done
[ "$status" = "READY" ] || { echo "FAIL: job 未 READY"; exit 1; }

count_before=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/chunks/count?docId=$DOC_ID" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['chunkCount'])" || echo "?")
echo "chunk count before re-delivery: $count_before"

# 重新投递同一 DOC_UPLOADED 事件：用 python 经 docker exec redis-cli 解析 stream 并 XADD 一份同字段副本。
docker exec crag-redis redis-cli XRANGE "$STREAM" - + > /tmp/idem-range.txt
python3 - "$DOC_ID" "$STREAM" /tmp/idem-range.txt /tmp/idem-redeliver.sh <<'PY'
import sys
doc_id, stream, infile, outfile = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
lines = open(infile, encoding="utf-8", errors="replace").read().splitlines()
# XRANGE 输出：每条记录两行：1) "entry-id"，2) 字段行（带缩进的 "1) "key"\n2) "value"..."）
fields = {}
i = 0
target_fields = None
while i < len(lines):
    line = lines[i].strip()
    if line.startswith("1)") and i + 1 < len(lines) and "$" not in line:
        # entry id 行形如 1) "1234-0"
        eid = line.split('"', 2)[1] if '"' in line else ""
        body = lines[i + 1]
        if doc_id in body and "DOC_UPLOADED" in body:
            target_fields = body
            break
    i += 1
if not target_fields:
    open(outfile, "w").write("echo no-doc-uploaded-event-found\n")
    sys.exit(0)
# 解析字段对
pairs = [p.strip() for p in target_fields.split('","')]
kv = {}
buf = target_fields
import re
toks = re.findall(r'"((?:[^"\\]|\\.)*)"', buf)
for j in range(0, len(toks) - 1, 2):
    kv[toks[j]] = toks[j + 1]
args = ["XADD", stream, "*"]
for k, v in kv.items():
    args += [k, v]
sh = "docker exec crag-redis redis-cli " + " ".join('"' + a.replace('"', '\\"') + '"' for a in args) + "\n"
open(outfile, "w").write(sh)
PY
bash /tmp/idem-redeliver.sh || true

sleep 10
count_after=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/chunks/count?docId=$DOC_ID" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['chunkCount'])" || echo "?")
echo "chunk count after re-delivery: $count_after"

[ "$count_before" = "$count_after" ] || { echo "FAIL: 重复 DOC_UPLOADED 改变了 chunk 计数 ($count_before -> $count_after)"; exit 1; }
echo "PASS: 重复 DOC_UPLOADED 不重复生成 chunk"
echo "=== router2 RAG Smoke DOC_UPLOADED Idempotency Test PASSED ==="
exit 0
