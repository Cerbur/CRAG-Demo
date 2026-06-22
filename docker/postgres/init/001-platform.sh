#!/bin/bash
set -euo pipefail

# CRAG Platform PostgreSQL 初始化脚本
# 在 POSTGRES_DB=crag_platform 创建的数据库内建立：
#   - extensions schema (vector, pg_trgm)
#   - 三个业务角色 (crag_access, crag_knowledge, crag_rag)
#   - 三个独立 Schema (access, knowledge, rag)
#   - 最小权限

DB="${POSTGRES_DB:-crag_platform}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-EOSQL
    -- 创建 extensions schema 并安装扩展
    CREATE SCHEMA IF NOT EXISTS extensions;
    CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions;
    CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA extensions;

    -- 创建三个业务角色
    CREATE ROLE crag_access WITH LOGIN PASSWORD '${CARG_ACCESS_PASSWORD:-access_demo}';
    CREATE ROLE crag_knowledge WITH LOGIN PASSWORD '${CARG_KNOWLEDGE_PASSWORD:-knowledge_demo}';
    CREATE ROLE crag_rag WITH LOGIN PASSWORD '${CARG_RAG_PASSWORD:-rag_demo}';

    -- 临时授予数据库 CREATE 权限以便创建自有 Schema
    GRANT CREATE ON DATABASE ${DB} TO crag_access;
    GRANT CREATE ON DATABASE ${DB} TO crag_knowledge;
    GRANT CREATE ON DATABASE ${DB} TO crag_rag;
EOSQL

# 以各角色身份创建自有 Schema
psql -v ON_ERROR_STOP=1 --username "crag_access" --dbname "$DB" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS access AUTHORIZATION crag_access;
EOSQL

psql -v ON_ERROR_STOP=1 --username "crag_knowledge" --dbname "$DB" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS knowledge AUTHORIZATION crag_knowledge;
EOSQL

psql -v ON_ERROR_STOP=1 --username "crag_rag" --dbname "$DB" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS rag AUTHORIZATION crag_rag;
EOSQL

# 撤销临时权限
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-EOSQL
    REVOKE CREATE ON DATABASE ${DB} FROM crag_access;
    REVOKE CREATE ON DATABASE ${DB} FROM crag_knowledge;
    REVOKE CREATE ON DATABASE ${DB} FROM crag_rag;

    -- 撤销 public schema 的 CREATE
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;

    -- 设置默认 search_path
    ALTER ROLE crag_access SET search_path TO access, pg_catalog;
    ALTER ROLE crag_knowledge SET search_path TO knowledge, pg_catalog;
    ALTER ROLE crag_rag SET search_path TO rag, extensions, pg_catalog;

    -- 授予 RAG 对 extensions 的 USAGE
    GRANT USAGE ON SCHEMA extensions TO crag_rag;

    -- 确保各角色对自己的 Schema 有完整权限
    GRANT ALL PRIVILEGES ON SCHEMA access TO crag_access;
    GRANT ALL PRIVILEGES ON SCHEMA knowledge TO crag_knowledge;
    GRANT ALL PRIVILEGES ON SCHEMA rag TO crag_rag;
EOSQL
