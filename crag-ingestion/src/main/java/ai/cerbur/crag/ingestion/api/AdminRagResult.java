package ai.cerbur.crag.ingestion.api;

/**
 * AdminRag 入库结果 —— 返回 docId、子 chunk 数量和状态.
 *
 * @param docId 文档唯一标识（UUID 字符串）
 * @param chunks 子级 child chunk 数量（不含 parent）
 * @param status 入库状态，"PENDING" 表示 chunk 已写入，Dense + Sparse 索引异步进行中
 * @since 2026-06-12
 */
public record AdminRagResult(String docId, int chunks, String status) {}
