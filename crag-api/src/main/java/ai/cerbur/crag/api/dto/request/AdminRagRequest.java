package ai.cerbur.crag.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * POST /api/v1/admin/rag 请求 DTO.
 *
 * <p>携带文档标题、纯文本内容和可选扩展元数据. 校验通过 Jakarta Bean Validation 声明式完成，由 AOP 层统一处理校验异常.
 *
 * @param title 文档标题，非空
 * @param content 文档纯文本内容，非空
 * @param metadata 可选扩展元数据（tags、source 等）
 * @since 2026-06-13
 */
public record AdminRagRequest(
    @NotBlank(message = "title is required") String title,
    @NotBlank(message = "content is required") String content,
    Map<String, Object> metadata) {}
