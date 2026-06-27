package ai.cerbur.crag.access.core.apikey;

/** 非法 API Key 状态迁移。gRPC 映射为 STATE_CONFLICT。 */
public class ApiKeyStateException extends RuntimeException {
  public ApiKeyStateException(String message) {
    super(message);
  }
}
