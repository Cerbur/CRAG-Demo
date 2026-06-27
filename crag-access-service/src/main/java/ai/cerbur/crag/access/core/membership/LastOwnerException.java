package ai.cerbur.crag.access.core.membership;

/** 移除或降级会令 Tenant 失去最后一名有效 OWNER。gRPC 映射为 LAST_OWNER。 */
public class LastOwnerException extends RuntimeException {
  public LastOwnerException() {
    super("cannot remove the last owner");
  }
}
