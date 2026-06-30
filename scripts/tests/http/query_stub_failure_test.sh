#!/bin/bash
# CRAG-Demo Query Stub Failure HTTP Regression (plan_7.hotfix_1)
# 在可召回的当前 ingestion evidence 上触发 failure Stub（502/50201/success=false），并在退出前恢复 success Stub
# 并复验成功链路。
#
# plan_7.hotfix_1 修正：原脚本 Phase 1 未 seed evidence，UserQueryService 在证据为空时短路返回 code=0
# （HTTP 200、不调用 LLM），failure 路径从不触发，脚本稳定失败。
#
# evidence 策略（执行期校准，见 plan_7.hotfix_1 变更记录 2026-06-30）：
# application.yml 设 spring.sql.init.mode: always，schema.sql 在每次 rag-service 启动时 DROP
# chunk/chunk_embedding/chunk_fts/ingestion_job，切换 CRAG_QUERY_LLM_STUB_MODE 必须重建 rag-service，
# 重建即清空全部召回证据。因此固定顺序：先按目标 Stub 模式重建 rag-service → 再 seed 唯一 KB → 等待
# ingestion READY → 再查询。Phase 1 在 failure Stub 下断言 502/50201/success=false（该 502 自证 evidence
# 已被召回，否则会短路返回 code=0）；Phase 2 恢复 success Stub 后重新 seed 并断言成功。
#
# 用法: bash scripts/tests/http/query_stub_failure_test.sh
#   固定端口：knowledge-service 8092、rag-service 8082（CRAG_SERVICE_PROFILES=smoke）。
#   脚本结束（含异常退出）自动将 rag-service 恢复为 success Stub；不清表、不删 volume。

set -euo pipefail

K_URL="${K_URL:-http://localhost:8092}"
R_URL="${R_URL:-http://localhost:8082}"
RUN_ID="qf-$(date +%s)-$$"
COMPOSE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
TIMEOUT=180
TENANT=$(date +%s)
WORK="$(mktemp -d /tmp/qf-XXXX)"
FAILED=0
_STUB_RESTORED=0

echo "=== Query Stub Failure HTTP Regression ==="
echo "K_URL=$K_URL  R_URL=$R_URL  RUN_ID=$RUN_ID  COMPOSE_DIR=$COMPOSE_DIR"

json_field() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2','-1'))" 2>/dev/null || echo "-1"
}
json_success() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN"
}
json_result() {
  printf '%s' "$1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('result',{}).get('$2',''))" 2>/dev/null || echo ""
}
sources_count() {
  printf '%s' "$1" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('result',{}).get('sources',[])))" 2>/dev/null || echo "0"
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

# 重建 rag-service 到指定 stub 模式并等待 readiness（schema.sql 冷启动重建会清空旧 evidence，
# 因此调用方必须在重建之后再 seed）。
rebuild_rag() {
  local mode="$1" label="$2"
  cd "$COMPOSE_DIR"
  echo "--- 重建 rag-service ($label, CRAG_QUERY_LLM_STUB_MODE=$mode) ---"
  CRAG_QUERY_LLM_STUB_MODE="$mode" CRAG_SERVICE_PROFILES=smoke docker compose up -d --build rag-service
  wait_ready "$R_URL" "rag-service($label)"
}

# seed 唯一 KB + 唯一 .txt，等待 RAG ingestion job READY；设置全局 SEED_KB / SEED_DOC。
# 入参：$1=verification code（全局唯一），$2=阶段标签（用于 KB 名与文件名）。
seed_and_wait() {
  local vc="$1" label="$2"
  local kb_name="qf-kb-${RUN_ID}-${label}"
  local kbResp
  kbResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/knowledge-bases" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT\",\"name\":\"$kb_name\",\"createdByUserId\":\"1\"}")
  SEED_KB=$(printf '%s' "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" 2>/dev/null || echo "")
  if [ -z "$SEED_KB" ]; then
    echo "FAIL: [$label] 创建知识库失败: $kbResp"
    return 1
  fi
  local file="$WORK/doc-${label}.txt"
  echo "${vc} CRAG-Demo 是一个基于 RAG 的问答机器人，使用 PostgreSQL 数据库和 pgvector 向量扩展进行混合检索。本段内容仅用于 Query Stub failure 回归（${label}）。" > "$file"
  local sha size upResp
  sha=$(sha256sum "$file" | awk '{print $1}')
  size=$(wc -c < "$file" | tr -d ' ')
  upResp=$(curl -s -X POST "$K_URL/api/v1/smoke/knowledge/documents/upload" \
    -F "tenantId=$TENANT" -F "knowledgeBaseId=$SEED_KB" -F "uploadedByUserId=1" \
    -F "sha256=$sha" -F "sizeBytes=$size" -F "file=@$file;filename=doc.txt")
  SEED_DOC=$(printf '%s' "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['docId'])" 2>/dev/null || echo "")
  local ingest
  ingest=$(printf '%s' "$upResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['ingestionStatus'])" 2>/dev/null || echo "")
  if [ -z "$SEED_DOC" ] || [ "$ingest" != "PENDING" ]; then
    echo "FAIL: [$label] 上传失败 (docId=$SEED_DOC status=$ingest): $upResp"
    return 1
  fi
  echo "PASS: [$label] knowledgeBaseId=$SEED_KB docId=$SEED_DOC ingestionStatus=$ingest"
  local status=""
  for _ in $(seq 1 $((TIMEOUT / 3))); do
    status=$(curl -s "$R_URL/api/v1/smoke/rag/ingestion/job?knowledgeBaseId=$SEED_KB&docId=$SEED_DOC" \
      | python3 -c "import sys,json; r=json.load(sys.stdin).get('result'); print(r['status'] if r else 'NONE')" 2>/dev/null || echo "ERR")
    [ "$status" = "READY" ] && return 0
    if [ "$status" = "FAILED" ]; then
      echo "FAIL: [$label] ingestion job FAILED (kb=$SEED_KB doc=$SEED_DOC)"
      docker compose logs --tail=60 rag-service knowledge-service 2>/dev/null || true
      return 1
    fi
    sleep 3
  done
  echo "FAIL: [$label] ingestion 未在超时内 READY (last=$status)"
  return 1
}

# trap：异常退出时确保恢复 success+smoke Stub 并清理临时目录。
restore_success_stub() {
  if [ "$_STUB_RESTORED" -eq 1 ]; then
    return
  fi
  _STUB_RESTORED=1
  echo ""
  echo "=== Trap: 异常退出，恢复 rag-service success+smoke Stub ==="
  rebuild_rag success "trap-restore" >/dev/null 2>&1 || true
}
cleanup() {
  rm -rf "$WORK" 2>/dev/null || true
  restore_success_stub
}
trap cleanup EXIT

# ── 0. 启动五项依赖服务（success+smoke）──
mkdir -p ./data/knowledge-files && chmod 777 ./data/knowledge-files
cd "$COMPOSE_DIR"
export CRAG_SERVICE_PROFILES=smoke
echo "--- 启动 db/redis/sidecar/knowledge-service/rag-service ---"
docker compose up -d --build db redis sidecar knowledge-service rag-service
wait_ready "$K_URL" "knowledge-service" || { docker compose logs --tail=40 knowledge-service 2>/dev/null || true; exit 1; }
wait_ready "$R_URL" "rag-service" || { docker compose logs --tail=40 rag-service 2>/dev/null || true; exit 1; }

VC_FAIL="verify-${RUN_ID}-fail"
VC_OK="verify-${RUN_ID}-ok"

# ════════════════════════════════════════════════════════════
# Phase 1: failure Stub —— 先重建（清空旧 evidence）→ seed → 断言 502/50201/success=false
# ════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 1: failure Stub —— 重建后 seed evidence 并查询 ==="
if ! rebuild_rag failure "failure mode"; then
  echo "FAIL: failure 模式 rag-service 未就绪"
  FAILED=1
fi
if ! seed_and_wait "$VC_FAIL" "fail"; then
  FAILED=1
fi

if [ "$FAILED" -eq 0 ]; then
  FAIL_RESP=$(curl -s -w '\n%{http_code}' -X POST "$R_URL/api/v1/smoke/query" \
    -H "Content-Type: application/json" \
    -d "{\"question\":\"${VC_FAIL} 使用什么数据库？\",\"knowledgeBaseId\":${SEED_KB}}" || printf '{"code":-1}\n000')
  FAIL_HTTP=$(printf '%s' "$FAIL_RESP" | tail -1)
  FAIL_BODY=$(printf '%s' "$FAIL_RESP" | sed '$d')
  echo "failure 查询 — HTTP $FAIL_HTTP (kb=$SEED_KB)"

  if [ "$FAIL_HTTP" = "502" ]; then
    echo "PASS: HTTP 502"
  else
    echo "FAIL: 期望 HTTP 502, 实际 $FAIL_HTTP"
    FAILED=1
  fi
  FAIL_CODE=$(json_field "$FAIL_BODY" code)
  if [ "$FAIL_CODE" = "50201" ]; then
    echo "PASS: code=50201（自证 evidence 已被召回并触达 failure LLM）"
  else
    echo "FAIL: 期望 code=50201, 实际 code=$FAIL_CODE"
    FAILED=1
  fi
  FAIL_SUCCESS=$(json_success "$FAIL_BODY")
  case "$FAIL_SUCCESS" in
    False|false) echo "PASS: success=false" ;;
    *) echo "FAIL: 期望 success=false, 实际 success=$FAIL_SUCCESS"; FAILED=1 ;;
  esac
fi

# ════════════════════════════════════════════════════════════
# Phase 2: 恢复 success Stub —— 重建后重新 seed → 断言成功链路
# ════════════════════════════════════════════════════════════
echo ""
echo "=== Phase 2: 恢复 success Stub —— 重建后重新 seed 并查询 ==="
if rebuild_rag success "restore success"; then
  _STUB_RESTORED=1
else
  echo "FAIL: 恢复 success 模式 rag-service 未就绪"
  FAILED=1
fi
if ! seed_and_wait "$VC_OK" "ok"; then
  FAILED=1
fi

if [ "$FAILED" -eq 0 ]; then
  RESTORE_RESP=$(curl -s -w '\n%{http_code}' -X POST "$R_URL/api/v1/smoke/query" \
    -H "Content-Type: application/json" \
    -d "{\"question\":\"${VC_OK} 使用什么数据库？\",\"knowledgeBaseId\":${SEED_KB}}" || printf '{"code":-1}\n000')
  RESTORE_HTTP=$(printf '%s' "$RESTORE_RESP" | tail -1)
  RESTORE_BODY=$(printf '%s' "$RESTORE_RESP" | sed '$d')

  R_CODE=$(json_field "$RESTORE_BODY" code)
  R_ANSWER=$(json_result "$RESTORE_BODY" answer)
  R_COUNT=$(sources_count "$RESTORE_BODY")
  if [ "$R_CODE" = "0" ]; then
    echo "PASS: 恢复后 code=0"
  else
    echo "FAIL: 恢复后 code=$R_CODE (期望 0), http=$RESTORE_HTTP"
    FAILED=1
  fi
  if [ "$R_ANSWER" = "已根据知识库证据生成回答。[S1]" ]; then
    echo "PASS: 恢复后固定 Stub answer"
  else
    echo "FAIL: 恢复后 answer='$R_ANSWER' (期望固定 Stub answer)"
    FAILED=1
  fi
  if [ "$R_COUNT" -gt 0 ]; then
    echo "PASS: 恢复后 sources 非空 ($R_COUNT)"
  else
    echo "FAIL: 恢复后 sources 为空"
    FAILED=1
  fi
fi

# ── Final ──
echo ""
echo "=== Test data preserved with RUN_ID=$RUN_ID ==="
if [ "$FAILED" -eq 0 ]; then
  echo "=== Query Stub Failure HTTP Regression PASSED ==="
  exit 0
else
  echo "=== Query Stub Failure HTTP Regression FAILED ==="
  exit 1
fi
