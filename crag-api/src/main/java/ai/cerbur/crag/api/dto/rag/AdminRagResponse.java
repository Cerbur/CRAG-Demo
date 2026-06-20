package ai.cerbur.crag.api.dto.rag;

import java.util.List;

/**
 * POST /api/v1/admin/rag 成功响应 DTO.
 *
 * <p>由 API 层从 AdminRagResult 映射，保持相同业务字段，避免直接序列化内部结果类型。
 *
 * @param docId 文档唯一标识（UUID 字符串）
 * @param chunks 子级 child chunk 数量（不含 parent）
 * @param status 入库状态，"PENDING" 表示 chunk 已写入，Dense + Sparse 索引异步进行中
 * @param parentChunkIds 预生成的 parent chunk ID 列表，防御性复制为不可变列表
 * @since 2026-06-19
 */
public record AdminRagResponse(
    String docId, int chunks, String status, List<String> parentChunkIds) {

  /** 紧凑构造器 —— 防御性复制，确保 parentChunkIds 永远不为 null 且构造后不受外部修改影响. */
  public AdminRagResponse {
    parentChunkIds = parentChunkIds == null ? List.of() : List.copyOf(parentChunkIds);
  }
}
