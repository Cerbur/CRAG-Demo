package ai.cerbur.crag.console.apikey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建 API Key 请求（plan_21/21.9）。
 *
 * <p>只接受 Key 名称与可选有效期；{@code actorUserId}/{@code tenantId}/{@code knowledgeBaseId} 来自
 * ConsolePrincipal/路径参数，不接受 body 覆盖，防越权。完整 Key 只在 create/rotate 响应中返回一次。
 *
 * @param name Key 名称，1–64 字符（与 Access {@code ApiKeyPolicy} 一致）。
 * @param ttlSeconds 有效期秒数；0 或省略使用 Access 默认（90 天），上限 365 天。禁止永不过期。
 */
public record CreateApiKeyRequest(
    @NotBlank(message = "name must not be blank")
        @Size(min = 1, max = 64, message = "name must be 1-64 chars")
        String name,
    @Min(value = 0, message = "ttlSeconds must be >= 0")
        @Max(value = 31_536_000, message = "ttlSeconds must be <= 365 days")
        Long ttlSeconds) {}
