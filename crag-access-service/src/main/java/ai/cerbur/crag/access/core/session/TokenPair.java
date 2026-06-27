package ai.cerbur.crag.access.core.session;

import java.time.Instant;

/**
 * 注册/登录/刷新成功后的 Access JWT 与 Refresh Token 材料。
 *
 * @param accessToken Access JWT
 * @param accessExpiresAt Access JWT 过期时刻
 * @param refreshToken 完整 Refresh Token（Base64URL 秘密），调用方须自行保存
 * @param refreshExpiresAt Refresh Token 过期时刻
 * @param sessionFamilyId Session Family ID（JWT 的 sid 指向它）
 */
public record TokenPair(
    String accessToken,
    Instant accessExpiresAt,
    String refreshToken,
    Instant refreshExpiresAt,
    long sessionFamilyId) {}
