package ai.cerbur.crag.access.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IdentityPolicy 纯单元测试：Username、Nickname 与密码的规范化与边界校验。 */
class IdentityPolicyTest {

  @Test
  @DisplayName("Username 去空白并转小写")
  void usernameNormalize() {
    assertEquals("alice", IdentityPolicy.normalizeUsername("  Alice  "));
    assertEquals("bob_123", IdentityPolicy.normalizeUsername("Bob_123"));
    assertEquals("a.b-c_d", IdentityPolicy.normalizeUsername("A.B-C_D"));
  }

  @Test
  @DisplayName("Username 长度边界 3-32")
  void usernameLengthBounds() {
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername("ab"));
    assertEquals("abc", IdentityPolicy.normalizeUsername("abc"));
    String max = "a".repeat(32);
    assertEquals(max, IdentityPolicy.normalizeUsername(max));
    assertThrows(
        IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername("a".repeat(33)));
  }

  @Test
  @DisplayName("Username 拒绝非法字符与空白")
  void usernameIllegalChars() {
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername("alice!"));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername("ali ce"));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername("中文用户"));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername(""));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeUsername("   "));
  }

  @Test
  @DisplayName("Nickname 去首尾空白并按 Unicode 码点计长 1-64")
  void nicknameNormalize() {
    assertEquals("Alice", IdentityPolicy.normalizeNickname("  Alice "));
    assertEquals("小明", IdentityPolicy.normalizeNickname("  小明 "));
    assertEquals("a".repeat(64), IdentityPolicy.normalizeNickname("a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class, () -> IdentityPolicy.normalizeNickname("a".repeat(65)));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeNickname(""));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.normalizeNickname("   "));
  }

  @Test
  @DisplayName("密码长度边界 12-128")
  void passwordLengthBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> IdentityPolicy.validatePassword("short1234".toCharArray()));
    IdentityPolicy.validatePassword("correct-horse-battery-12".toCharArray());
    char[] max = new char[128];
    java.util.Arrays.fill(max, 'x');
    IdentityPolicy.validatePassword(max);
    char[] tooLong = new char[129];
    java.util.Arrays.fill(tooLong, 'x');
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.validatePassword(tooLong));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.validatePassword(null));
  }
}
