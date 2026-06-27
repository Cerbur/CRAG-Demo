package ai.cerbur.crag.access.core.apikey;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ApiKeyPolicy 纯单元测试：名称、TTL 与完整 Key 格式解析。 */
class ApiKeyPolicyTest {

  @Test
  @DisplayName("名称校验 1-64 且去空白")
  void validateName() {
    assertEquals("my-key", ApiKeyPolicy.validateName(" my-key "));
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.validateName(""));
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.validateName("   "));
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.validateName(null));
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.validateName("a".repeat(65)));
  }

  @Test
  @DisplayName("TTL 默认 90 天，上限 365 天，禁止非正或永久")
  void resolveTtl() {
    assertEquals(Duration.ofDays(90), ApiKeyPolicy.resolveTtl(null));
    assertEquals(Duration.ofDays(10), ApiKeyPolicy.resolveTtl(Duration.ofDays(10)));
    assertEquals(Duration.ofDays(365), ApiKeyPolicy.resolveTtl(Duration.ofDays(365)));
    assertThrows(
        IllegalArgumentException.class, () -> ApiKeyPolicy.resolveTtl(Duration.ofDays(366)));
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.resolveTtl(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> ApiKeyPolicy.resolveTtl(Duration.ofDays(-1)));
  }

  @Test
  @DisplayName("合法完整 Key 解析出前缀与秘密")
  void parseCompleteKeyValid() {
    ApiKeyPolicy.ParsedKey parsed = ApiKeyPolicy.parseCompleteKey("crag_abcdefghijkm_secretpart");
    assertEquals("abcdefghijkm", parsed.prefix());
    assertEquals("secretpart", parsed.secret());
  }

  @Test
  @DisplayName("非法完整 Key 格式被拒绝")
  void parseCompleteKeyInvalid() {
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.parseCompleteKey("notcrag"));
    assertThrows(
        IllegalArgumentException.class, () -> ApiKeyPolicy.parseCompleteKey("crag_short_secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiKeyPolicy.parseCompleteKey("crag_has_undersco_secret"));
    assertThrows(
        IllegalArgumentException.class, () -> ApiKeyPolicy.parseCompleteKey("crag_abcdefghijkl_"));
    assertThrows(IllegalArgumentException.class, () -> ApiKeyPolicy.parseCompleteKey(null));
  }

  @Test
  @DisplayName("拼接完整 Key 与解析互逆")
  void buildAndParseRoundTrip() {
    String key = ApiKeyPolicy.buildCompleteKey("prefix123456", "abc");
    ApiKeyPolicy.ParsedKey parsed = ApiKeyPolicy.parseCompleteKey(key);
    assertEquals("prefix123456", parsed.prefix());
    assertEquals("abc", parsed.secret());
  }
}
