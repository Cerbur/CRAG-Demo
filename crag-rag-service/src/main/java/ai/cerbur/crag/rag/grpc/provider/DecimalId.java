package ai.cerbur.crag.rag.grpc.provider;

/** gRPC 边界十进制 ID 解析；非法格式抛 {@link IllegalArgumentException}（Plan 21.4，与 Access DecimalId 对齐）. */
public final class DecimalId {

  private DecimalId() {}

  public static long parse(String value, String fieldName) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(fieldName + " must be a decimal string: " + value);
    }
  }
}
