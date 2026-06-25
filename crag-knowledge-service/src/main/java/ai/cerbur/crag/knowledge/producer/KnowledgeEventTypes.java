package ai.cerbur.crag.knowledge.producer;

/** Knowledge 事件常量：事件类型、生产者名、资源类型与 payload 版本。 */
public final class KnowledgeEventTypes {

  private KnowledgeEventTypes() {}

  /** 上传完成事件。 */
  public static final String DOC_UPLOADED = "DOC_UPLOADED";

  /** 事件生产者标识。 */
  public static final String PRODUCER = "knowledge-service";

  /** Document 资源类型。 */
  public static final String RESOURCE_DOCUMENT = "DOCUMENT";

  /** payload 结构版本。 */
  public static final int PAYLOAD_VERSION = 1;
}
