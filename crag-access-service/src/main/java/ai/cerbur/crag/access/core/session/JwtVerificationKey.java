package ai.cerbur.crag.access.core.session;

/** Access JWT 验签公钥，PEM 形式，供 router4 本地验签。 */
public record JwtVerificationKey(String kid, String algorithm, String publicKeyPem) {}
