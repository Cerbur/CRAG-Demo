package ai.cerbur.crag.console.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * KnowledgeBase HTTP 响应投影（plan_21/21.8）。
 *
 * <p>包含 {@code apiKeyReady} 标志位表达建库部分成功：Knowledge 已建库但 Access Scope 暂时失败时为 {@code false}，
 * 资源本身已创建（HTTP 201）。不包含任何文件存储、storage key 或内部路径信息。
 *
 * @param knowledgeBaseId 知识库 ID（十进制字符串）
 * @param tenantId 租户 ID（十进制字符串）
 * @param name 展示名
 * @param apiKeyReady Access Scope 是否就绪（决定是否可立即创建 API Key）
 * @param createdAt 创建时间（RFC 3339 UTC）
 * @param updatedAt 更新时间（RFC 3339 UTC）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record KnowledgeBaseResponse(
    String knowledgeBaseId,
    String tenantId,
    String name,
    boolean apiKeyReady,
    Instant createdAt,
    Instant updatedAt) {}
