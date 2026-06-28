package ai.cerbur.crag.console.security.jwt;

/**
 * Console 已认证请求主体（plan_21/21.6）。
 *
 * <p>从 Access JWT 的 {@code sub}（userId）与 {@code sid}（sessionFamilyId）解析得到，仅建立身份/会话上下文；不信任 Tenant 或
 * Role。Tenant 级别权限由后续 Controller 通过实时 gRPC 授权获取。
 *
 * @param userId 用户 ID（来自 sub）
 * @param sessionFamilyId 会话 Family ID（来自 sid）
 */
public record ConsolePrincipal(long userId, long sessionFamilyId) {}
