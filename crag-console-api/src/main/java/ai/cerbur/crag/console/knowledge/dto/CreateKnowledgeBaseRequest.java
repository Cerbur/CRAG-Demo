package ai.cerbur.crag.console.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建库请求（plan_21/21.8）。
 *
 * <p>只接受展示名；{@code actorUserId} 与 {@code tenantId} 来自 ConsolePrincipal/路径参数，不接受 body 覆盖，防越权。
 */
public record CreateKnowledgeBaseRequest(
    @NotBlank(message = "name must not be blank")
        @Size(min = 1, max = 128, message = "name must be 1-128 chars")
        String name) {}
