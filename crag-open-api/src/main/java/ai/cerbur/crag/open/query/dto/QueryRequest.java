package ai.cerbur.crag.open.query.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Open Query 请求体（plan_21/21.10）。
 *
 * <p>只接受 {@code question}；去除首尾空白后 1–2000 个 Unicode 字符。不接受 {@code tenantId} 或 {@code
 * knowledgeBaseId}（由 Key 决定）。
 */
public record QueryRequest(@NotBlank @Size(min = 1, max = 2000) String question) {}
