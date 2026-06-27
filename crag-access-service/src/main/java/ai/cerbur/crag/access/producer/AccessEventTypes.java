package ai.cerbur.crag.access.producer;

/** Access 事件常量：事件类型、生产者名、资源类型与 payload 版本。 */
public final class AccessEventTypes {

  private AccessEventTypes() {}

  /** API Key 缓存失效事件。 */
  public static final String API_KEY_INVALIDATED = "API_KEY_INVALIDATED";

  /** 事件生产者标识。 */
  public static final String PRODUCER = "access-service";

  /** 单 Key 变化资源类型。 */
  public static final String RESOURCE_API_KEY = "API_KEY";

  /** Scope 终态阻塞资源类型。 */
  public static final String RESOURCE_API_KEY_SCOPE = "API_KEY_SCOPE";

  /** payload 结构版本。 */
  public static final int PAYLOAD_VERSION = 1;
}
