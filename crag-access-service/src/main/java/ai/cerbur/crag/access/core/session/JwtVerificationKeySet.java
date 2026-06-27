package ai.cerbur.crag.access.core.session;

import java.util.List;

/** Access JWT 验签公钥集。 */
public record JwtVerificationKeySet(List<JwtVerificationKey> keys) {}
