-- ============================================================
-- CRAG-Demo — RAG Service 数据库 DDL
-- 数据库: PostgreSQL 17 + pgvector
-- 扩展 (vector, pg_trgm) 由平台初始化脚本在 extensions schema 中管理
-- 通过 Spring sql.init.mode=always 在启动时自动执行
-- ============================================================

-- Plan 15 冷切换：丢弃旧 UUID/VARCHAR 模式的 RAG 数据，重建为 BIGINT 列
-- 仅作用于 RAG 三张表，不删除 PostgreSQL volume 或其他服务 schema
DROP TABLE IF EXISTS chunk_fts CASCADE;
DROP TABLE IF EXISTS chunk_embedding CASCADE;
DROP TABLE IF EXISTS chunk CASCADE;

-- Chunk 表：文档分块存储（child + parent 两种粒度）
-- Plan 15 起所有 ID 切换为 BIGINT，由应用层 CragIdGenerator 预生成 Snowflake ID
CREATE TABLE chunk (
    chunk_id         BIGINT PRIMARY KEY,
    doc_id           BIGINT NOT NULL,              -- 关联文档 ID（Snowflake LEGACY_DOCUMENT）
    parent_chunk_id  BIGINT NOT NULL DEFAULT 0,   -- 0=parent chunk，其他=child 指向 parent
    chunk_index      INTEGER,                     -- child 在 parent 中的序号（从 0 开始；parent 为 NULL）
    content          TEXT NOT NULL,               -- chunk 文本内容
    token_count      INTEGER,                     -- token 数量
    metadata         JSONB DEFAULT '{}',          -- 扩展元数据 {tags, ...}
    dense_status     SMALLINT DEFAULT 0,           -- Dense/Embedding 链路: 0=init 1=processing 2=success 3=failed 4=skipped
    sparse_status    SMALLINT DEFAULT 0,           -- Sparse/FTS 链路:   0=init 1=processing 2=success 3=failed 4=skipped
    version          INTEGER DEFAULT 0 NOT NULL,   -- 乐观锁版本号，每次 UPDATE 自动 +1（JPA @Version）
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

-- 查询索引
CREATE INDEX IF NOT EXISTS idx_chunk_dense_status ON chunk(dense_status);
CREATE INDEX IF NOT EXISTS idx_chunk_sparse_status ON chunk(sparse_status);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_id ON chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_parent ON chunk(parent_chunk_id);

-- ============================================================
-- Chunk Embedding 表（Dense 向量存储，与 chunk 主表解耦）
-- 职责：存储 child chunk 的 embedding 向量，独立生命周期（换模型可 truncate + 重算）
-- ============================================================
CREATE TABLE chunk_embedding (
    chunk_id    BIGINT PRIMARY KEY REFERENCES chunk(chunk_id) ON DELETE CASCADE,
    embedding   vector(768) NOT NULL,
    version     INTEGER DEFAULT 0 NOT NULL,          -- 乐观锁版本号，每次 UPDATE 自动 +1（JPA @Version）
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()              -- 最后更新时间
);

-- HNSW 索引：余弦相似度（vector_cosine_ops）。HNSW 在 INSERT 时增量维护图，
-- 不存在「空表建索引、之后插入的向量无法被 ANN 检索」的失效问题——ivfflat 在
-- 空表上建索引时 k-means 无中心，会导致 ORDER BY embedding <=> q LIMIT 的查询
-- 返回 0 行（见 plan_6.hotfix_7.1）。采用 pgvector 默认构建参数（m / ef_construction）
-- 与查询 ef_search，对 demo 数据量结果等价精确。
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_vector
    ON chunk_embedding USING hnsw (embedding vector_cosine_ops);

-- ============================================================
-- Chunk FTS 表（Sparse 全文检索，与 chunk 主表解耦）
-- 职责：存储 child chunk 的 tsvector 分词结果，独立生命周期（换分词策略可重建）
-- ============================================================
CREATE TABLE chunk_fts (
    chunk_id    BIGINT PRIMARY KEY REFERENCES chunk(chunk_id) ON DELETE CASCADE,
    fts_content tsvector NOT NULL,
    version     INTEGER DEFAULT 0 NOT NULL,          -- 乐观锁版本号，每次 UPDATE 自动 +1（JPA @Version）
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()              -- 最后更新时间
);

CREATE INDEX IF NOT EXISTS idx_chunk_fts_content
    ON chunk_fts USING GIN (fts_content);
