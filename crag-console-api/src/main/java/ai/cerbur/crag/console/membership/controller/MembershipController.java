package ai.cerbur.crag.console.membership.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.console.membership.dto.AddMemberRequest;
import ai.cerbur.crag.console.membership.dto.ChangeMemberRoleRequest;
import ai.cerbur.crag.console.membership.dto.MemberResponse;
import ai.cerbur.crag.console.membership.dto.MembersListResponse;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console Membership HTTP 入口（plan_21/21.7）。
 *
 * <p>路由：list/add/change-role/remove。actor userId 只来自 Bearer filter 注入的 {@link
 * ConsolePrincipal}，不接受请求体中的 actorUserId，防越权。MEMBER 不能管理（Access 实时授权返回 PERMISSION_DENIED →
 * 403）；跨租户不可见（NOT_FOUND → 404，不泄漏）；最后 OWNER 保护（FAILED_PRECONDITION → 409）。
 *
 * <p>DELETE 返回 HTTP 200 与已变更（REMOVED）投影的 Response（计划约定）。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/members")
@Validated
public class MembershipController {

  private final AccessMembershipClient membershipClient;

  public MembershipController(AccessMembershipClient membershipClient) {
    this.membershipClient = membershipClient;
  }

  @GetMapping
  public ResponseEntity<Response<MembersListResponse>> list(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @RequestParam(value = "pageSize", defaultValue = "20")
          @Min(value = 1, message = "pageSize must be >= 1")
          @Max(value = 100, message = "pageSize must be <= 100")
          int pageSize,
      @RequestParam(value = "pageToken", defaultValue = "") String pageToken) {
    if (principal == null) {
      return ResponseEntity.status(401)
          .body(Response.error(ai.cerbur.crag.common.dto.result.ResponseCode.UNAUTHENTICATED));
    }
    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("pageSize must be 1-100");
    }
    MembersListResponse page =
        membershipClient.listMembers(principal.userId(), tenantId, pageSize, pageToken);
    return ResponseEntity.ok(Response.success(page));
  }

  @PostMapping
  public ResponseEntity<Response<MemberResponse>> add(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @Valid @RequestBody AddMemberRequest body) {
    if (principal == null) {
      return ResponseEntity.status(401)
          .body(Response.error(ai.cerbur.crag.common.dto.result.ResponseCode.UNAUTHENTICATED));
    }
    MemberResponse member =
        membershipClient.addMember(principal.userId(), tenantId, body.username());
    return ResponseEntity.ok(Response.success(member));
  }

  @PatchMapping("/{userId}")
  public ResponseEntity<Response<MemberResponse>> changeRole(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long userId,
      @Valid @RequestBody ChangeMemberRoleRequest body) {
    if (principal == null) {
      return ResponseEntity.status(401)
          .body(Response.error(ai.cerbur.crag.common.dto.result.ResponseCode.UNAUTHENTICATED));
    }
    MemberResponse member =
        membershipClient.changeRole(principal.userId(), tenantId, userId, body.role());
    return ResponseEntity.ok(Response.success(member));
  }

  @DeleteMapping("/{userId}")
  public ResponseEntity<Response<MemberResponse>> remove(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long userId) {
    if (principal == null) {
      return ResponseEntity.status(401)
          .body(Response.error(ai.cerbur.crag.common.dto.result.ResponseCode.UNAUTHENTICATED));
    }
    // 计划约定：DELETE 返回 HTTP 200 与已变更 REMOVED 投影的 Response
    MemberResponse member = membershipClient.removeMember(principal.userId(), tenantId, userId);
    return ResponseEntity.ok(Response.success(member));
  }
}
