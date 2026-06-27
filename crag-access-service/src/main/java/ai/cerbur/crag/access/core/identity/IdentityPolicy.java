package ai.cerbur.crag.access.core.identity;

/**
 * Identity 输入规则：Username、Nickname 与密码规范化与校验。
 *
 * <p>纯静态逻辑，无 Spring 依赖，可单元测试。Username 去除首尾空白并转 ASCII 小写，长度 3–32，只允许字母、数字、点、下划线和短横线； Nickname
 * 去除首尾空白，长度 1–64 个 Unicode 字符；密码长度 12–128，不要求人为组合大小写或特殊字符。
 */
public final class IdentityPolicy {

  private IdentityPolicy() {}

  /** 规范化并校验 Username，返回 ASCII 小写形式。 */
  public static String normalizeUsername(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("username must not be null");
    }
    String normalized = raw.trim().toLowerCase();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    int length = normalized.length();
    if (length < 3 || length > 32) {
      throw new IllegalArgumentException("username length must be 3-32");
    }
    for (int i = 0; i < length; i++) {
      char c = normalized.charAt(i);
      if (!isAllowedUsernameChar(c)) {
        throw new IllegalArgumentException("username contains illegal character: " + c);
      }
    }
    return normalized;
  }

  /** 规范化并校验 Nickname，返回去空白后的展示名。 */
  public static String normalizeNickname(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("nickname must not be null");
    }
    String normalized = raw.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("nickname must not be blank");
    }
    int codePoints = normalized.codePointCount(0, normalized.length());
    if (codePoints < 1 || codePoints > 64) {
      throw new IllegalArgumentException("nickname length must be 1-64 unicode characters");
    }
    return normalized;
  }

  /** 校验密码长度；不要求人为组合。 */
  public static void validatePassword(char[] password) {
    if (password == null) {
      throw new IllegalArgumentException("password must not be null");
    }
    if (password.length < 12 || password.length > 128) {
      throw new IllegalArgumentException("password length must be 12-128");
    }
  }

  private static boolean isAllowedUsernameChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
  }
}
