package ai.cerbur.crag.knowledge.controller.smoke.dto;

import jakarta.validation.constraints.NotBlank;

/** smoke 创建知识库请求；ID 为十进制字符串。 */
public record CreateKnowledgeBaseSmokeRequest(
    @NotBlank String tenantId, @NotBlank String name, @NotBlank String createdByUserId) {}
