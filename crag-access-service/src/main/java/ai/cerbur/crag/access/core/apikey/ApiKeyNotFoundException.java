package ai.cerbur.crag.access.core.apikey;

import ai.cerbur.crag.access.core.identity.InvalidCredentialsException;

/** API Key 未找到；继承凭据无效异常，统一不泄漏存在性。 */
public class ApiKeyNotFoundException extends InvalidCredentialsException {}
