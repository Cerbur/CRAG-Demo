package ai.cerbur.crag.knowledge.producer;

/** Knowledge 事件常量：事件类型、生产者名、资源类型与 payload 版本。 */
public final class KnowledgeEventTypes {

  private KnowledgeEventTypes() {}

  /** 上传完成事件。 */
  public static final String DOC_UPLOADED = "DOC_UPLOADED";

  /** 知识库创建事件（plan_21/21.3）：Knowledge 创建 KB 后同事务发布，Access 消费后幂等补齐 Scope。 */
  public static final String KNOWLEDGE_BASE_CREATED = "KNOWLEDGE_BASE_CREATED";

  /** 事件生产者标识。 */
  public static final String PRODUCER = "knowledge-service";

  /** Document 资源类型。 */
  public static final String RESOURCE_DOCUMENT = "DOCUMENT";

  /** KnowledgeBase 资源类型。 */
  public static final String RESOURCE_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";

  /** payload 结构版本。 */
  public static final int PAYLOAD_VERSION = 1;
}
