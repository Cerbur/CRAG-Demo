package ai.cerbur.crag.console.tenant.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import ai.cerbur.crag.console.tenant.dto.TenantListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console Tenant HTTP 入口（plan_21/21.7）。
 *
 * <p>路由：GET /api/v1/tenants。actor userId 只来自 Bearer filter 注入的 {@link
 * ConsolePrincipal}，不读取请求体或查询参数中的 actor，防越权。分页使用 tenantId 游标，保证稳定。
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Validated
public class TenantController {

  private final AccessIdentityClient identityClient;

  public TenantController(AccessIdentityClient identityClient) {
    this.identityClient = identityClient;
  }

  @GetMapping
  public ResponseEntity<Response<TenantListResponse>> listTenants(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
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
    AccessIdentityClient.TenantsPage page =
        identityClient.listTenantsPage(principal.userId(), pageSize, pageToken);
    return ResponseEntity.ok(
        Response.success(new TenantListResponse(page.items(), page.nextPageToken())));
  }
}
