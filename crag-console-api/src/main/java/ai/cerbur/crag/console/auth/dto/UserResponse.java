package ai.cerbur.crag.console.auth.dto;

/** 用户安全投影（plan_21/21.6）。不含密码、账号状态或登录标识。 */
public record UserResponse(String userId, String nickname) {}
