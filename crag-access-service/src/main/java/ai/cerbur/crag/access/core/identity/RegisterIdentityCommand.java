package ai.cerbur.crag.access.core.identity;

/**
 * 注册命令。密码以 {@code char[]} 传入，调用方负责在使用后清零。
 *
 * @param nickname 展示名（未规范化）
 * @param username 登录 Username（未规范化）
 * @param password 明文密码，使用后清零
 */
public record RegisterIdentityCommand(String nickname, String username, char[] password) {}
