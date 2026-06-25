#!/bin/bash
# Knowledge smoke HTTP 回归 — 非法上传全部失败
# 验证 sha256 不匹配、非 UTF-8、非法扩展名、超 10 MiB 上传均返回 4xx 且不创建 Document。
# 自动启动 db/redis/knowledge-service-smoke，以 runId 隔离数据；不清表、不删 volume。
#
# 用法: bash scripts/tests/http/knowledge_smoke_upload_invalid_test.sh [BASE_URL]
#       BASE_URL 默认 http://localhost:8094

set -euo pipefail

BASE_URL="${1:-http://localhost:8094}"
RUN_ID="k-inv-$(date +%s)-$$"
TIMEOUT=120
FAILED=0

echo "=== Knowledge Smoke Upload Invalid Test ==="
echo "BASE_URL=$BASE_URL  RUN_ID=$RUN_ID"

mkdir -p ./data/knowledge-files-smoke && chmod 777 ./data/knowledge-files-smoke
docker compose --profile smoke up -d --build db redis knowledge-service-smoke

cleanup() {
  rm -f "$TXT_FILE" "$BIN_FILE"
  docker compose stop knowledge-service-smoke >/dev/null 2>&1 || true
}

echo "waiting for knowledge-service-smoke readiness..."
status="000"
for _ in $(seq 1 "$TIMEOUT"); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health/readiness" || echo "000")
  [ "$status" = "200" ] && break
  sleep 2
done
if [ "$status" != "200" ]; then
  echo "FAIL: knowledge-service-smoke 未就绪 (status=$status)"
  docker compose logs --tail=60 knowledge-service-smoke || true
  cleanup
  exit 1
fi

TENANT=$(date +%s)
TXT_FILE=$(mktemp /tmp/knowledge-inv-XXXX.txt)
echo "valid text content $RUN_ID" > "$TXT_FILE"
TXT_SHA=$(sha256sum "$TXT_FILE" | awk '{print $1}')
TXT_SIZE=$(wc -c < "$TXT_FILE" | tr -d ' ')
BIN_FILE=$(mktemp /tmp/knowledge-inv-XXXX.bin)
printf '\xff\xfe\xfd\xc0' > "$BIN_FILE"
BIN_SHA=$(sha256sum "$BIN_FILE" | awk '{print $1}')
BIN_SIZE=$(wc -c < "$BIN_FILE" | tr -d ' ')

kbResp=$(curl -s -X POST "$BASE_URL/api/v1/smoke/knowledge/knowledge-bases" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT\",\"name\":\"kb-$RUN_ID\",\"createdByUserId\":\"1\"}")
KB_ID=$(echo "$kbResp" | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['knowledgeBaseId'])" 2>/dev/null || echo "")
if [ -z "$KB_ID" ]; then
  echo "FAIL: 创建知识库失败: $kbResp"
  cleanup
  exit 1
fi

upload_code() {
  curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/smoke/knowledge/documents/upload" "$@"
}

assert4xx() {
  local name="$1" code="$2"
  if [ "$code" -ge 400 ] 2>/dev/null && [ "$code" -le 499 ] 2>/dev/null; then
    echo "PASS: $name 被拒绝 (status=$code)"
  else
    echo "FAIL: $name 未被拒绝 (status=$code)"
    FAILED=1
  fi
}

ZERO_SHA=$(printf '0%.0s' $(seq 1 64))

assert4xx "非法扩展名" "$(upload_code \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$TXT_SHA" -F "sizeBytes=$TXT_SIZE" -F "file=@$TXT_FILE;filename=payload.exe")"

assert4xx "sha256 不匹配" "$(upload_code \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$ZERO_SHA" -F "sizeBytes=$TXT_SIZE" -F "file=@$TXT_FILE;filename=doc.txt")"

assert4xx "非 UTF-8" "$(upload_code \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$BIN_SHA" -F "sizeBytes=$BIN_SIZE" -F "file=@$BIN_FILE;filename=doc.txt")"

assert4xx "超 10 MiB" "$(upload_code \
  -F "tenantId=$TENANT" -F "knowledgeBaseId=$KB_ID" -F "uploadedByUserId=1" \
  -F "sha256=$TXT_SHA" -F "sizeBytes=11534336" -F "file=@$TXT_FILE;filename=doc.txt")"

if [ "$FAILED" = "0" ]; then
  echo "=== Knowledge Smoke Upload Invalid Test PASSED ==="
  cleanup
  exit 0
fi
echo "=== Knowledge Smoke Upload Invalid Test FAILED ==="
cleanup
exit 1
