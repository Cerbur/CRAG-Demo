package ai.cerbur.crag.knowledge.core.knowledgebase;

/**
 * 知识库在指定租户下不存在时抛出。
 *
 * <p>用于 permission-safe 的 not found 语义：跨租户资源不存在与真实不存在统一表现为 not found，避免泄漏资源存在性。入口层据此映射为 NOT_FOUND
 * 类错误。
 */
public class KnowledgeBaseNotFoundException extends RuntimeException {

  public KnowledgeBaseNotFoundException(long tenantId, long knowledgeBaseId) {
    super("knowledge base " + knowledgeBaseId + " not found for tenant " + tenantId);
  }
}
