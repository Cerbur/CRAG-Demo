#!/bin/bash
# scripts/tests/test_wait_helpers.sh
#
# plan_10.hotfix_1：验证 http/lib/wait_helpers.sh 的 deadline 轮询在可控 hang HTTP 端点下
# 不会因单次 curl timeout 成倍超出 max_wait。
#
# 原缺陷：wait_for_http_status 等函数的 elapsed 只累加固定 sleep（5s），未计入 curl -m 的
# 实际阻塞时间；端点 hang 时每轮 ~curl_timeout+sleep 只让 elapsed 增加 5，导致真实墙钟耗时
# 随循环次数成倍放大（max_wait=120 实际 ~16 分钟）。
#
# 本测试不依赖 Docker：启动本地 HTTP 端点，/ok 立即返回 200+UP，其他路径永久 hang，
# 以短 max_wait 断言 helper 返回非零且耗时仅允许少量秒级调度误差。
#
# 用法: bash scripts/tests/test_wait_helpers.sh
# 依赖: bash、python3、curl。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/tests/http/lib/wait_helpers.sh
source "$SCRIPT_DIR/http/lib/wait_helpers.sh"

EXIT_CODE=0

# ─── 本地可控 HTTP 端点 ───
# /ok → 200 + {"status":"UP"}；其他路径 → 永久 hang（模拟 db 故障下 readiness 因 health
# indicator 阻塞而无法返回）。

PORT_FILE="$(mktemp)"
SERVER_PID=""
cleanup() {
  local rc=$?
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$PORT_FILE"
  exit "$rc"
}
trap cleanup EXIT

python3 - "$PORT_FILE" <<'PY' &
import http.server
import socketserver
import sys
import time


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/ok"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"UP"}')
        else:
            # 模拟被等待端点 hang：accept 后永不响应
            time.sleep(3600)

    def log_message(self, *args):
        pass


socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer.daemon_threads = True
httpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
with open(sys.argv[1], "w") as f:
    f.write(str(httpd.server_address[1]))
httpd.serve_forever()
PY
SERVER_PID=$!

# 等待 server 写入端口
for _ in $(seq 1 50); do
  [ -s "$PORT_FILE" ] && break
  sleep 0.1
done
if [ ! -s "$PORT_FILE" ]; then
  echo "FAIL: 本地 hang server 未启动"
  exit 1
fi
HANG_PORT="$(cat "$PORT_FILE")"
echo "本地可控端点启动于 127.0.0.1:${HANG_PORT}（/ok → 200，其他 → hang）"

MAX_WAIT="${MAX_WAIT:-8}"
TOLERANCE="${TOLERANCE:-5}"
UPPER=$((MAX_WAIT + TOLERANCE))

# ─── 正向：wait_for_http_status 命中 200 立即返回 ───
echo ""
echo "=== 正向：wait_for_http_status 命中 HTTP 200 ==="
if wait_for_http_status "http://127.0.0.1:$HANG_PORT/ok" "200" "ok endpoint" "$MAX_WAIT"; then
  echo "  PASS: ok endpoint 立即返回 200"
else
  echo "  FAIL: ok endpoint 应返回 200"
  EXIT_CODE=1
fi

# ─── 核心：wait_for_http_status 在 hang 端点下受 deadline 约束 ───
echo ""
echo "=== 核心：wait_for_http_status 在 hang 端点下受 max_wait=${MAX_WAIT} 约束 ==="
START=$(date +%s)
if wait_for_http_status "http://127.0.0.1:$HANG_PORT/hang" "200" "hang endpoint" "$MAX_WAIT"; then
  echo "  FAIL: hang 端点不应返回 200"
  EXIT_CODE=1
  HANG_RC=0
else
  HANG_RC=$?
  echo "  PASS: hang 端点返回非零（rc=${HANG_RC}）"
fi
ELAPSED=$(( $(date +%s) - START ))
if [ "${HANG_RC:-1}" -ne 0 ]; then
  if [ "$ELAPSED" -le "$UPPER" ]; then
    echo "  PASS: 实际耗时 ${ELAPSED}s <= 上限 ${UPPER}s（max_wait=${MAX_WAIT}，容差 ${TOLERANCE}s）"
  else
    echo "  FAIL: 实际耗时 ${ELAPSED}s 超过上限 ${UPPER}s，deadline 未生效（原缺陷下应 ~80s+）"
    EXIT_CODE=1
  fi
fi

# ─── 核心：wait_for_health_endpoint 同样受 deadline 约束（curl_timeout 被 deadline 裁剪）───
echo ""
echo "=== 核心：wait_for_health_endpoint 在 hang 端点下受 max_wait=${MAX_WAIT} 约束 ==="
START=$(date +%s)
if wait_for_health_endpoint "$HANG_PORT" "/hang" "200" "hang health endpoint" "$MAX_WAIT" 35; then
  echo "  FAIL: hang health endpoint 不应返回 200"
  EXIT_CODE=1
  HEALTH_RC=0
else
  HEALTH_RC=$?
  echo "  PASS: hang health endpoint 返回非零（rc=${HEALTH_RC}）"
fi
ELAPSED=$(( $(date +%s) - START ))
if [ "${HEALTH_RC:-1}" -ne 0 ]; then
  if [ "$ELAPSED" -le "$UPPER" ]; then
    echo "  PASS: 实际耗时 ${ELAPSED}s <= 上限 ${UPPER}s（curl_timeout=35 被 deadline 裁剪到剩余秒数）"
  else
    echo "  FAIL: 实际耗时 ${ELAPSED}s 超过上限 ${UPPER}s，deadline 未生效"
    EXIT_CODE=1
  fi
fi

# ─── 正向：wait_for_health_endpoint 命中 200 且校验 status=UP ───
echo ""
echo "=== 正向：wait_for_health_endpoint 命中 HTTP 200 + status=UP ==="
if wait_for_health_endpoint "$HANG_PORT" "/ok" "200" "ok health endpoint" "$MAX_WAIT" 35; then
  echo "  PASS: ok health endpoint 返回 200 且 status=UP"
else
  echo "  FAIL: ok health endpoint 应返回 200 且 status=UP"
  EXIT_CODE=1
fi

echo ""
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "=== test_wait_helpers PASSED ==="
  exit 0
else
  echo "=== test_wait_helpers FAILED ==="
  exit 1
fi
