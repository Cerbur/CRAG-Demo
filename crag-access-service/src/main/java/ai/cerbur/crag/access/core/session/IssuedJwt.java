package ai.cerbur.crag.access.core.session;

import java.time.Instant;

/** 签发的 Access JWT。 */
public record IssuedJwt(String token, Instant expiresAt) {}
