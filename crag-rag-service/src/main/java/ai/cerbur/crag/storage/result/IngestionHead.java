package ai.cerbur.crag.storage.result;

import java.util.Objects;

/**
 * Ingestion head 当前指针（Plan 21.4）—— 按 docId 维护当前 operationVersion 的不可变投影.
 *
 * <p>由 {@code IngestionHeadDao} 读取或 CAS 推进后返回。携带 {@code knowledgeBaseId}、{@code docId}、当前 {@code
 * operationVersion} 与乐观锁 {@code version}。调用方据此判断迟到事件是否已被取代、新版本能否抢占， 以及召回路径的版本过滤基线.
 *
 * @param knowledgeBaseId 知识库 ID
 * @param docId 文档 ID
 * @param operationVersion 当前 operationVersion
 * @param version 乐观锁版本号
 */
public record IngestionHead(long knowledgeBaseId, long docId, long operationVersion, long version) {

  public IngestionHead {
    if (docId == 0L) {
      throw new IllegalArgumentException("docId must not be 0");
    }
    if (operationVersion <= 0L) {
      throw new IllegalArgumentException("operationVersion must be positive");
    }
    if (version < 0L) {
      throw new IllegalArgumentException("version must not be negative");
    }
    Objects.requireNonNull(knowledgeBaseId);
  }
}
