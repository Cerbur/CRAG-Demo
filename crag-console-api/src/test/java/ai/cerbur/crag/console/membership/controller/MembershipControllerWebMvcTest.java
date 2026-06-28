package ai.cerbur.crag.console.membership.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.cerbur.crag.console.advice.GlobalExceptionHandler;
import ai.cerbur.crag.console.membership.dto.ChangeMemberRoleRequest;
import ai.cerbur.crag.console.membership.dto.MemberResponse;
import ai.cerbur.crag.console.membership.dto.MembersListResponse;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.ConflictException;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.ForbiddenException;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.NotFoundException;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * MembershipController HTTP 契约 MockMvc 测试（plan_21/21.7）。
 *
 * <p>standaloneSetup 装配真实 Controller + GlobalExceptionHandler；AccessMembershipClient 使用 Mockito
 * 替身。锁定 list/add/change-role/remove 路由、状态码、actor 来源与负向映射（403 MEMBER、404 跨租户、409 最后 OWNER）。
 */
class MembershipControllerWebMvcTest {

  private MockMvc mvc;
  private final ObjectMapper om = new ObjectMapper();
  private AccessMembershipClient membershipClient;

  @BeforeEach
  void setUp() {
    membershipClient = mock(AccessMembershipClient.class);
    MembershipController controller = new MembershipController(membershipClient);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("GET /api/v1/tenants/{tenantId}/members 无 Principal → 401")
  void listMembersUnauthenticatedReturns401() throws Exception {
    mvc.perform(get("/api/v1/tenants/1/members").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("GET /api/v1/tenants/{tenantId}/members → 200，items + nextPageToken；actor=principal")
  void listMembersReturnsPaginated() throws Exception {
    when(membershipClient.listMembers(eq(123L), eq(1L), eq(20), eq("")))
        .thenReturn(
            new MembersListResponse(
                List.of(
                    new MemberResponse(
                        "1",
                        "alice",
                        "OWNER",
                        "ACTIVE",
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-29T00:00:00Z")),
                    new MemberResponse(
                        "2",
                        "bob",
                        "MEMBER",
                        "ACTIVE",
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-29T00:00:00Z"))),
                "1"));

    mvc.perform(
            get("/api/v1/tenants/1/members")
                .param("pageSize", "20")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.result.items[0].userId").value("1"))
        .andExpect(jsonPath("$.result.items[0].nickname").value("alice"))
        .andExpect(jsonPath("$.result.items[0].role").value("OWNER"))
        .andExpect(jsonPath("$.result.items[1].userId").value("2"))
        .andExpect(jsonPath("$.result.nextPageToken").value("1"));

    verify(membershipClient).listMembers(123L, 1L, 20, "");
  }

  @Test
  @DisplayName("GET members 跨租户不可见 → 404（不泄漏存在性）")
  void listMembersCrossTenantReturns404() throws Exception {
    when(membershipClient.listMembers(anyLong(), eq(99L), anyInt(), any()))
        .thenThrow(new NotFoundException());

    mvc.perform(
            get("/api/v1/tenants/99/members")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  @DisplayName("GET members 非 tenant 成员 → 403 FORBIDDEN")
  void listMembersForbiddenReturns403() throws Exception {
    when(membershipClient.listMembers(anyLong(), anyLong(), anyInt(), any()))
        .thenThrow(new ForbiddenException());

    mvc.perform(
            get("/api/v1/tenants/1/members")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  @DisplayName("POST /api/v1/tenants/{tenantId}/members → 200，返回新增 Member")
  void addMemberReturnsMember() throws Exception {
    when(membershipClient.addMember(eq(123L), eq(1L), eq("bob")))
        .thenReturn(
            new MemberResponse(
                "2",
                "bob",
                "MEMBER",
                "ACTIVE",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T00:00:00Z")));

    mvc.perform(
            post("/api/v1/tenants/1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bob\"}")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.userId").value("2"))
        .andExpect(jsonPath("$.result.nickname").value("bob"))
        .andExpect(jsonPath("$.result.role").value("MEMBER"));

    verify(membershipClient).addMember(123L, 1L, "bob");
  }

  @Test
  @DisplayName("POST members 非法 username 校验 → 400 VALIDATION_ERROR")
  void addMemberValidationReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/tenants/1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\"}")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("POST members MEMBER 无权管理 → 403 FORBIDDEN")
  void addMemberForbiddenReturns403() throws Exception {
    when(membershipClient.addMember(anyLong(), anyLong(), any()))
        .thenThrow(new ForbiddenException());

    mvc.perform(
            post("/api/v1/tenants/1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bob\"}")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  @DisplayName("POST members 用户不存在 → 404（统一不泄漏存在性）")
  void addMemberUserNotFoundReturns404() throws Exception {
    when(membershipClient.addMember(anyLong(), anyLong(), any()))
        .thenThrow(new NotFoundException());

    mvc.perform(
            post("/api/v1/tenants/1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ghost\"}")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  @DisplayName("PATCH /api/v1/tenants/{tenantId}/members/{userId} → 200，返回变更后 Member")
  void changeRoleReturnsMember() throws Exception {
    when(membershipClient.changeRole(eq(123L), eq(1L), eq(2L), eq("OWNER")))
        .thenReturn(
            new MemberResponse(
                "2",
                "bob",
                "OWNER",
                "ACTIVE",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T00:05:00Z")));

    mvc.perform(
            patch("/api/v1/tenants/1/members/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new ChangeMemberRoleRequest("OWNER")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.role").value("OWNER"));

    verify(membershipClient).changeRole(123L, 1L, 2L, "OWNER");
  }

  @Test
  @DisplayName("PATCH members 最后 OWNER 降级 → 409 CONFLICT")
  void changeRoleLastOwnerReturns409() throws Exception {
    when(membershipClient.changeRole(anyLong(), anyLong(), anyLong(), any()))
        .thenThrow(new ConflictException());

    mvc.perform(
            patch("/api/v1/tenants/1/members/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new ChangeMemberRoleRequest("MEMBER")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }

  @Test
  @DisplayName("PATCH members 非法 role 校验 → 400 VALIDATION_ERROR")
  void changeRoleValidationReturns400() throws Exception {
    mvc.perform(
            patch("/api/v1/tenants/1/members/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"\"}")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("DELETE /api/v1/tenants/{tenantId}/members/{userId} → 200 REMOVED 投影")
  void removeMemberReturnsRemovedProjection() throws Exception {
    when(membershipClient.removeMember(eq(123L), eq(1L), eq(2L)))
        .thenReturn(
            new MemberResponse(
                "2",
                "bob",
                "MEMBER",
                "REMOVED",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T00:10:00Z")));

    mvc.perform(
            delete("/api/v1/tenants/1/members/2")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.userId").value("2"))
        .andExpect(jsonPath("$.result.status").value("REMOVED"));

    verify(membershipClient).removeMember(123L, 1L, 2L);
  }

  @Test
  @DisplayName("DELETE members 最后 OWNER 移除 → 409 CONFLICT")
  void removeMemberLastOwnerReturns409() throws Exception {
    when(membershipClient.removeMember(anyLong(), anyLong(), anyLong()))
        .thenThrow(new ConflictException());

    mvc.perform(
            delete("/api/v1/tenants/1/members/1")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }

  @Test
  @DisplayName("DELETE members MEMBER 越权管理 → 403 FORBIDDEN")
  void removeMemberForbiddenReturns403() throws Exception {
    when(membershipClient.removeMember(anyLong(), anyLong(), anyLong()))
        .thenThrow(new ForbiddenException());

    mvc.perform(
            delete("/api/v1/tenants/1/members/2")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  @DisplayName("DELETE members 跨租户 → 404 不泄漏")
  void removeMemberCrossTenantReturns404() throws Exception {
    when(membershipClient.removeMember(anyLong(), anyLong(), anyLong()))
        .thenThrow(new NotFoundException());

    mvc.perform(
            delete("/api/v1/tenants/99/members/2")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  @DisplayName("任意 member 操作 Access 不可用 → 503 DOWNSTREAM_UNAVAILABLE")
  void downstreamUnavailableReturns503() throws Exception {
    when(membershipClient.listMembers(anyLong(), anyLong(), anyInt(), any()))
        .thenThrow(new DownstreamUnavailableException());

    mvc.perform(
            get("/api/v1/tenants/1/members")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(50301));
  }
}
