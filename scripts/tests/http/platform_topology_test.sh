#!/bin/bash
set -euo pipefail

# ============================================================
# CRAG-Demo — 平台拓扑验收测试
# 验证五进程 Docker 拓扑、服务身份、数据库权限和镜像边界
# ============================================================

RUN_ID="topology_$(date +%s)"
ADMIN_PASSWORD="${POSTGRES_ADMIN_PASSWORD:-admin_demo}"
DB_CONTAINER="crag-db"

echo "=========================================="
echo "Platform Topology Test"
echo "Run ID: ${RUN_ID}"
echo "=========================================="

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ $1${NC}"; }
fail() { echo -e "${RED}✗ $1${NC}"; exit 1; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }

# ============================================================
# 1. 验证七个长期服务健康
# ============================================================
echo ""
echo "--- 1. 检查服务健康状态 ---"

EXPECTED_SERVICES=("crag-db" "crag-sidecar" "crag-access-service" "crag-knowledge-service" "crag-rag-service" "crag-console-api" "crag-open-api")

for service in "${EXPECTED_SERVICES[@]}"; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$service" 2>/dev/null || echo "not_found")
  if [ "$status" = "healthy" ]; then
    pass "$service is healthy"
  else
    fail "$service is not healthy (status: $status)"
  fi
done

# ============================================================
# 2. 验证五个 Java 容器正确 Jar
# ============================================================
echo ""
echo "--- 2. 检查 Java 容器 Jar ---"

JAVA_SERVICES=("crag-access-service" "crag-knowledge-service" "crag-rag-service" "crag-console-api" "crag-open-api")

for service in "${JAVA_SERVICES[@]}"; do
  jar_count=$(docker exec "$service" sh -c 'ls /app/*.jar 2>/dev/null | wc -l' || echo "0")
  if [ "$jar_count" = "1" ]; then
    pass "$service has exactly one jar"
  else
    fail "$service has $jar_count jars (expected 1)"
  fi
done

# ============================================================
# 3. 验证非 root 用户
# ============================================================
echo ""
echo "--- 3. 检查容器用户 ---"

for service in "${JAVA_SERVICES[@]}"; do
  user=$(docker exec "$service" whoami 2>/dev/null || echo "unknown")
  if [ "$user" != "root" ]; then
    pass "$service runs as non-root user: $user"
  else
    fail "$service runs as root"
  fi
done

# ============================================================
# 4. 验证内部端口不暴露
# ============================================================
echo ""
echo "--- 4. 检查端口暴露 ---"

# 检查数据库端口不暴露
db_ports=$(docker port "$DB_CONTAINER" 2>/dev/null || echo "")
if [ -z "$db_ports" ]; then
  pass "Database port not exposed to host"
else
  fail "Database port exposed: $db_ports"
fi

# 检查内部服务端口不暴露
INTERNAL_SERVICES=("crag-access-service" "crag-knowledge-service")
for service in "${INTERNAL_SERVICES[@]}"; do
  ports=$(docker port "$service" 2>/dev/null || echo "")
  if [ -z "$ports" ]; then
    pass "$service internal ports not exposed"
  else
    fail "$service ports exposed: $ports"
  fi
done

# ============================================================
# 5. 验证管理员凭据不进入 Java 容器
# ============================================================
echo ""
echo "--- 5. 检查管理员凭据隔离 ---"

for service in "${JAVA_SERVICES[@]}"; do
  env_output=$(docker exec "$service" env 2>/dev/null || echo "")
  if echo "$env_output" | grep -q "POSTGRES_ADMIN_PASSWORD"; then
    fail "$service has admin password variable in environment"
  fi
  if echo "$env_output" | grep -q "$ADMIN_PASSWORD"; then
    fail "$service has admin password value in environment"
  fi
  pass "$service does not have admin password"
done

# ============================================================
# 6. 验证合法 Probe 链路
# ============================================================
echo ""
echo "--- 6. 检查 Probe 链路 ---"

# Console API readiness 应该包含下游 Probe
console_health=$(curl -s --fail http://localhost:8080/actuator/health 2>/dev/null || echo "{}")
if echo "$console_health" | grep -q "downstreamConnectivity"; then
  if echo "$console_health" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('details',{}).get('downstreamConnectivity',{}).get('status')=='UP' else 1)" 2>/dev/null; then
    pass "Console API downstreamConnectivity is UP"
  else
    fail "Console API downstreamConnectivity is not UP"
  fi
else
  fail "Console API downstreamConnectivity not found in health response"
fi

# Open API readiness 应该包含下游 Probe
open_health=$(curl -s --fail http://localhost:8081/actuator/health 2>/dev/null || echo "{}")
if echo "$open_health" | grep -q "downstreamConnectivity"; then
  if echo "$open_health" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('details',{}).get('downstreamConnectivity',{}).get('status')=='UP' else 1)" 2>/dev/null; then
    pass "Open API downstreamConnectivity is UP"
  else
    fail "Open API downstreamConnectivity is not UP"
  fi
else
  fail "Open API downstreamConnectivity not found in health response"
fi

# ============================================================
# 7. 验证 Schema owner
# ============================================================
echo ""
echo "--- 7. 检查 Schema owner ---"

# 检查 access schema owner
access_owner=$(docker exec "$DB_CONTAINER" psql -U crag_admin -d crag_platform -t -c \
  "SELECT schema_owner FROM information_schema.schemata WHERE schema_name = 'access';" 2>/dev/null | tr -d ' ')
if [ "$access_owner" = "crag_access" ]; then
  pass "access schema owned by crag_access"
else
  fail "access schema owner is '$access_owner', expected 'crag_access'"
fi

# 检查 knowledge schema owner
knowledge_owner=$(docker exec "$DB_CONTAINER" psql -U crag_admin -d crag_platform -t -c \
  "SELECT schema_owner FROM information_schema.schemata WHERE schema_name = 'knowledge';" 2>/dev/null | tr -d ' ')
if [ "$knowledge_owner" = "crag_knowledge" ]; then
  pass "knowledge schema owned by crag_knowledge"
else
  fail "knowledge schema owner is '$knowledge_owner', expected 'crag_knowledge'"
fi

# 检查 rag schema owner
rag_owner=$(docker exec "$DB_CONTAINER" psql -U crag_admin -d crag_platform -t -c \
  "SELECT schema_owner FROM information_schema.schemata WHERE schema_name = 'rag';" 2>/dev/null | tr -d ' ')
if [ "$rag_owner" = "crag_rag" ]; then
  pass "rag schema owned by crag_rag"
else
  fail "rag schema owner is '$rag_owner', expected 'crag_rag'"
fi

# ============================================================
# 8. 验证同 Schema 成功
# ============================================================
echo ""
echo "--- 8. 检查同 Schema 访问 ---"

# crag_access 应该能在 access schema 创建表
docker exec "$DB_CONTAINER" psql -U crag_access -d crag_platform -c \
  "CREATE TABLE IF NOT EXISTS access.test_${RUN_ID} (id serial PRIMARY KEY);" 2>/dev/null
if [ $? -eq 0 ]; then
  pass "crag_access can create table in access schema"
else
  fail "crag_access cannot create table in access schema"
fi

# crag_knowledge 应该能在 knowledge schema 创建表
docker exec "$DB_CONTAINER" psql -U crag_knowledge -d crag_platform -c \
  "CREATE TABLE IF NOT EXISTS knowledge.test_${RUN_ID} (id serial PRIMARY KEY);" 2>/dev/null
if [ $? -eq 0 ]; then
  pass "crag_knowledge can create table in knowledge schema"
else
  fail "crag_knowledge cannot create table in knowledge schema"
fi

# crag_rag 应该能在 rag schema 创建表
docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -c \
  "CREATE TABLE IF NOT EXISTS rag.test_${RUN_ID} (id serial PRIMARY KEY);" 2>/dev/null
if [ $? -eq 0 ]; then
  pass "crag_rag can create table in rag schema"
else
  fail "crag_rag cannot create table in rag schema"
fi

# crag_access 应该能查询自身 schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_access -d crag_platform -t -c \
  "SELECT count(*) FROM access.test_${RUN_ID};" 2>/dev/null | tr -d ' ')
if [ "$result" = "0" ]; then
  pass "crag_access can query own schema table"
else
  fail "crag_access cannot query own schema table"
fi

# crag_knowledge 应该能查询自身 schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_knowledge -d crag_platform -t -c \
  "SELECT count(*) FROM knowledge.test_${RUN_ID};" 2>/dev/null | tr -d ' ')
if [ "$result" = "0" ]; then
  pass "crag_knowledge can query own schema table"
else
  fail "crag_knowledge cannot query own schema table"
fi

# crag_rag 应该能查询自身 schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -t -c \
  "SELECT count(*) FROM rag.test_${RUN_ID};" 2>/dev/null | tr -d ' ')
if [ "$result" = "0" ]; then
  pass "crag_rag can query own schema table"
else
  fail "crag_rag cannot query own schema table"
fi

# ============================================================
# 9. 验证跨 Schema SELECT/CREATE 失败
# ============================================================
echo ""
echo "--- 9. 检查跨 Schema 访问拒绝 ---"

# crag_access 不应该能 SELECT rag schema 的表（表在同 Schema 成功阶段已创建）
result=$(docker exec "$DB_CONTAINER" psql -U crag_access -d crag_platform -c \
  "SELECT * FROM rag.test_${RUN_ID} LIMIT 1;" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_access correctly denied SELECT on rag schema"
else
  fail "crag_access was not denied SELECT on rag schema: $result"
fi

# crag_access 不应该能在 knowledge schema 创建表
result=$(docker exec "$DB_CONTAINER" psql -U crag_access -d crag_platform -c \
  "CREATE TABLE knowledge.test_cross_${RUN_ID} (id serial PRIMARY KEY);" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_access correctly denied CREATE in knowledge schema"
else
  fail "crag_access was not denied CREATE in knowledge schema: $result"
fi

# crag_access 不应该能 SELECT knowledge schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_access -d crag_platform -c \
  "SELECT * FROM knowledge.test_${RUN_ID} LIMIT 1;" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_access correctly denied SELECT on knowledge schema"
else
  fail "crag_access was not denied SELECT on knowledge schema: $result"
fi

# crag_knowledge 不应该能 SELECT access schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_knowledge -d crag_platform -c \
  "SELECT * FROM access.test_${RUN_ID} LIMIT 1;" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_knowledge correctly denied SELECT on access schema"
else
  fail "crag_knowledge was not denied SELECT on access schema: $result"
fi

# crag_knowledge 不应该能在 rag schema 创建表
result=$(docker exec "$DB_CONTAINER" psql -U crag_knowledge -d crag_platform -c \
  "CREATE TABLE rag.test_cross_${RUN_ID} (id serial PRIMARY KEY);" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_knowledge correctly denied CREATE in rag schema"
else
  fail "crag_knowledge was not denied CREATE in rag schema: $result"
fi

# crag_knowledge 不应该能 SELECT rag schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_knowledge -d crag_platform -c \
  "SELECT * FROM rag.test_${RUN_ID} LIMIT 1;" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_knowledge correctly denied SELECT on rag schema"
else
  fail "crag_knowledge was not denied SELECT on rag schema: $result"
fi

# crag_rag 不应该能 SELECT access schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -c \
  "SELECT * FROM access.test_${RUN_ID} LIMIT 1;" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_rag correctly denied SELECT on access schema"
else
  fail "crag_rag was not denied SELECT on access schema: $result"
fi

# crag_rag 不应该能在 access schema 创建表
result=$(docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -c \
  "CREATE TABLE access.test_cross_${RUN_ID} (id serial PRIMARY KEY);" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_rag correctly denied CREATE in access schema"
else
  fail "crag_rag was not denied CREATE in access schema: $result"
fi

# crag_rag 不应该能 SELECT knowledge schema 的表
result=$(docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -c \
  "SELECT * FROM knowledge.test_${RUN_ID} LIMIT 1;" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_rag correctly denied SELECT on knowledge schema"
else
  fail "crag_rag was not denied SELECT on knowledge schema: $result"
fi

# crag_rag 不应该能在 knowledge schema 创建表
result=$(docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -c \
  "CREATE TABLE knowledge.test_cross_${RUN_ID} (id serial PRIMARY KEY);" 2>&1 || true)
if echo "$result" | grep -q "permission denied"; then
  pass "crag_rag correctly denied CREATE in knowledge schema"
else
  fail "crag_rag was not denied CREATE in knowledge schema: $result"
fi

# ============================================================
# 10. 清理临时测试表
# ============================================================
echo ""
echo "--- 10. 清理临时测试表 ---"

docker exec "$DB_CONTAINER" psql -U crag_access -d crag_platform -c \
  "DROP TABLE IF EXISTS access.test_${RUN_ID};" 2>/dev/null && \
  pass "cleaned access test table" || warn "access test table cleanup skipped"

docker exec "$DB_CONTAINER" psql -U crag_knowledge -d crag_platform -c \
  "DROP TABLE IF EXISTS knowledge.test_${RUN_ID};" 2>/dev/null && \
  pass "cleaned knowledge test table" || warn "knowledge test table cleanup skipped"

docker exec "$DB_CONTAINER" psql -U crag_rag -d crag_platform -c \
  "DROP TABLE IF EXISTS rag.test_${RUN_ID};" 2>/dev/null && \
  pass "cleaned rag test table" || warn "rag test table cleanup skipped"

# ============================================================
# 11. 验证日志脱敏
# ============================================================
echo ""
echo "--- 11. 检查日志脱敏 ---"

for service in "${JAVA_SERVICES[@]}"; do
  logs=$(docker logs "$service" 2>&1 | tail -100 || echo "")
  if echo "$logs" | grep -q "console-token-demo\|open-token-demo"; then
    fail "$service logs contain service tokens"
  else
    pass "$service logs do not contain service tokens"
  fi
done

# ============================================================
# 总结
# ============================================================
echo ""
echo "=========================================="
echo -e "${GREEN}All platform topology tests passed!${NC}"
echo "=========================================="
