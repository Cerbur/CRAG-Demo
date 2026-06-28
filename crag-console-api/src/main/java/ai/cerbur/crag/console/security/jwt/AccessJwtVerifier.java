package ai.cerbur.crag.console.security.jwt;

/**
 * Access JWT 验签器（plan_21/21.6）。
 *
 * <p>校验 RS256 签名与 {@code kid/alg/iss/aud/exp/nbf} 声明，解析 {@code sub}/{@code sid} 为 {@link
 * ConsolePrincipal}。 未知 {@code kid} 抛 {@link UnknownJwtKidException}，由调用方触发一次公钥刷新。
 */
public interface AccessJwtVerifier {

  /**
   * 校验并解析 Access JWT。
   *
   * @param rawToken 形如 {@code xxx.yyy.zzz} 的 Access JWT
   * @return 请求主体
   * @throws InvalidJwtException 验签或声明失败
   * @throws UnknownJwtKidException kid 未在缓存中
   */
  ConsolePrincipal verify(String rawToken);
}
