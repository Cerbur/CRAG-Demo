package ai.cerbur.crag.access.core.membership;

/**
 * 实时权限判断结果。拒绝原因不泄漏成员关系细节。
 *
 * @param allowed 是否允许
 * @param action 被判断的动作
 * @param reason 拒绝原因展示值（允许时为空）
 */
public record AuthorizationDecision(boolean allowed, TenantAction action, String reason) {

  /** 构造允许决策。 */
  public static AuthorizationDecision allow(TenantAction action) {
    return new AuthorizationDecision(true, action, "");
  }

  /** 构造拒绝决策。 */
  public static AuthorizationDecision deny(TenantAction action) {
    return new AuthorizationDecision(false, action, "permission denied");
  }
}
