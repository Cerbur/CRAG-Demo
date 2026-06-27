package ai.cerbur.crag.access.producer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** ApiKeyInvalidatedPayload 纯单元测试：payload 形状与序列化不泄漏完整 Key、HMAC 或 Pepper。 */
class ApiKeyInvalidatedPayloadTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("payload 只携带定位与版本字段")
  void payloadShape() {
    ApiKeyInvalidatedPayload payload =
        new ApiKeyInvalidatedPayload(
            AccessEventTypes.RESOURCE_API_KEY, 123L, 10L, 800L, "DISABLED", 1L);
    assertEquals(AccessEventTypes.RESOURCE_API_KEY, payload.resourceType());
    assertEquals(123L, payload.resourceId());
    assertEquals(10L, payload.tenantId());
    assertEquals(800L, payload.knowledgeBaseId());
    assertEquals("DISABLED", payload.action());
    assertEquals(1L, payload.resourceVersion());
  }

  @Test
  @DisplayName("序列化结果不含完整 Key、秘密、HMAC 或 Pepper")
  void serializationIsSafe() throws Exception {
    ApiKeyInvalidatedPayload payload =
        new ApiKeyInvalidatedPayload(
            AccessEventTypes.RESOURCE_API_KEY_SCOPE, 800L, 10L, 800L, "SCOPE_BLOCKED", 2L);
    String json = JSON.writeValueAsString(payload);
    assertTrue(json.contains("\"resourceType\":\"API_KEY_SCOPE\""));
    assertTrue(json.contains("\"action\":\"SCOPE_BLOCKED\""));
    assertFalse(json.toLowerCase().contains("secret"), "payload must not carry secret");
    assertFalse(json.toLowerCase().contains("hmac"), "payload must not carry hmac");
    assertFalse(json.toLowerCase().contains("pepper"), "payload must not carry pepper");
    assertFalse(json.toLowerCase().contains("completekey"), "payload must not carry complete key");
  }
}
