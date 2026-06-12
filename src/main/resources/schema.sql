-- ============================================================
-- CRAG-Demo — 数据库初始化 DDL
-- 数据库: PostgreSQL 17 + pgvector
-- 通过 Spring sql.init.mode=always 在启动时自动执行
-- ============================================================

-- pgvector 扩展（向量相似度检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- pg_trgm 扩展（三元组模糊匹配，辅助中文 FTS）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Chunk 表：文档分块存储（child + parent 两种粒度）
CREATE TABLE IF NOT EXISTS chunk (
    chunk_id         VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    doc_id           VARCHAR(36) NOT NULL,              -- 关联文档 ID
    parent_chunk_id  VARCHAR(36) NOT NULL DEFAULT '',  -- ''=parent chunk，其他=child 指向 parent
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
CREATE TABLE IF NOT EXISTS chunk_embedding (
    chunk_id    VARCHAR(36) PRIMARY KEY REFERENCES chunk(chunk_id) ON DELETE CASCADE,
    embedding   vector(768) NOT NULL,
    version     INTEGER DEFAULT 0 NOT NULL,          -- 乐观锁版本号，每次 UPDATE 自动 +1（JPA @Version）
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()              -- 最后更新时间
);

-- IVFFlat 索引：余弦相似度，数据量上万后考虑切 HNSW
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_vector
    ON chunk_embedding USING ivfflat (embedding vector_cosine_ops);

-- ============================================================
-- Chunk FTS 表（Sparse 全文检索，与 chunk 主表解耦）
-- 职责：存储 child chunk 的 tsvector 分词结果，独立生命周期（换分词策略可重建）
-- ============================================================
CREATE TABLE IF NOT EXISTS chunk_fts (
    chunk_id    VARCHAR(36) PRIMARY KEY REFERENCES chunk(chunk_id) ON DELETE CASCADE,
    fts_content tsvector NOT NULL,
    version     INTEGER DEFAULT 0 NOT NULL,          -- 乐观锁版本号，每次 UPDATE 自动 +1（JPA @Version）
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()              -- 最后更新时间
);

CREATE INDEX IF NOT EXISTS idx_chunk_fts_content
    ON chunk_fts USING GIN (fts_content);
