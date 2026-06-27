package ai.cerbur.crag.access.security;

import java.nio.CharBuffer;
import java.util.Arrays;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * 基于 Spring Security 的 Argon2id 密码哈希实现。
 *
 * <p>参数受 {@link AccessSecurityProperties.Argon2} 控制，不得低于 64 MiB / 3 次迭代 / 并行度 1 / 16 字节 Salt / 32
 * 字节输出基线。 明文以 {@link CharBuffer} 透传，避免创建不可变 {@link String}；调用方负责在使用后清零原 {@code char[]}。
 */
public class Argon2PasswordHasher implements PasswordHasher {

  private final Argon2PasswordEncoder delegate;

  public Argon2PasswordHasher(
      int saltLength, int hashLength, int parallelism, int memoryKiB, int iterations) {
    this.delegate =
        new Argon2PasswordEncoder(saltLength, hashLength, parallelism, memoryKiB, iterations);
  }

  @Override
  public String hash(char[] password) {
    CharBuffer buffer = CharBuffer.wrap(password);
    try {
      return delegate.encode(buffer);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  @Override
  public boolean matches(char[] password, String encodedHash) {
    CharBuffer buffer = CharBuffer.wrap(password);
    try {
      return delegate.matches(buffer, encodedHash);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
