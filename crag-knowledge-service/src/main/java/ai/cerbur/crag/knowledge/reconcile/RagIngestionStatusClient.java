package ai.cerbur.crag.knowledge.reconcile;

import java.time.Instant;
import java.util.Optional;

/**
 * RAG Ingestion Status RPC 客户端抽象（plan_21/21.5）。
 *
 * <p>Reconciler 通过本接口在数据库事务外查询 RAG 权威 Job 状态，并按需终态化滞留 PROCESSING 任务。 本接口是跨进程边界，调用不得在数据库事务内执行。
 *
 * <p>实现可以是 in-process gRPC stub（生产）或测试替身。
 */
public interface RagIngestionStatusClient {

  /**
   * 查询当前 (doc, operationVersion) 的权威 Job 状态投影。
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @return 状态投影；RAG 未找到 Job 或不可达时为 empty
   */
  Optional<RagIngestionStatus> getStatus(
      long tenantId, long knowledgeBaseId, long docId, long operationVersion);

  /**
   * 对滞留 PROCESSING 任务以 RAG status/version CAS 终态化为安全超时失败。
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param staleBefore 视为滞留的 startedAt 上界
   * @return 终态化后的状态投影；RAG 未找到 Job 或 CAS 失败时为 empty
   */
  Optional<RagIngestionStatus> markTimedOut(
      long tenantId, long knowledgeBaseId, long docId, long operationVersion, Instant staleBefore);
}
