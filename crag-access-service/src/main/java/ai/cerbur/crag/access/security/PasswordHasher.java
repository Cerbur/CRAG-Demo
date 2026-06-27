package ai.cerbur.crag.access.security;

/**
 * 密码哈希契约。实现使用 Argon2id，输出可持久化的编码串；明文 {@code char[]} 由调用方在使用后清零。
 *
 * <p>哈希不可逆；{@link #matches} 用于凭据校验，恒定时间比较由底层实现保证。
 */
public interface PasswordHasher {

  /** 计算 Argon2id 编码串。调用方负责在使用后清零传入的 {@code password}。 */
  String hash(char[] password);

  /** 校验明文密码是否匹配已存储的 Argon2id 编码串。 */
  boolean matches(char[] password, String encodedHash);
}
