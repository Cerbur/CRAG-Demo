package ai.cerbur.crag.access.core.session;

import ai.cerbur.crag.access.core.identity.InvalidCredentialsException;

/** Refresh Token 无效、过期、已撤销或被复用检测命中。继承 {@link InvalidCredentialsException}，统一映射为凭据无效。 */
public class InvalidRefreshTokenException extends InvalidCredentialsException {}
