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
    chunk_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id           UUID NOT NULL,              -- 关联文档 ID
    parent_chunk_id  UUID,                        -- NULL=parent, 非NULL=child（指向 parent）
    chunk_index      INTEGER,                     -- child 在 parent 中的序号（从 0 开始；parent 为 NULL）
    content          TEXT NOT NULL,               -- chunk 文本内容
    token_count      INTEGER,                     -- token 数量
    metadata         JSONB DEFAULT '{}',          -- 扩展元数据 {tags, ...}
    status           VARCHAR(16) DEFAULT 'init',  -- init / processing / success / failed
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

-- 查询索引
CREATE INDEX IF NOT EXISTS idx_chunk_status ON chunk(status);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_id ON chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_parent ON chunk(parent_chunk_id);
