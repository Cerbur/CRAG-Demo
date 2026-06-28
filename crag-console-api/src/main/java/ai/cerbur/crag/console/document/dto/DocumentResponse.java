package ai.cerbur.crag.console.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Document HTTP 响应投影（plan_21/21.8）。
 *
 * <p>包含完整摄取投影：{@code ingestionStatus}、{@code operationVersion}、{@code attempt}、失败安全字段、{@code
 * retryable} 标志和时间。不包含 storage key、文件路径或文件内容。
 *
 * <p>{@code retryable} 由 Console 根据 {@code ingestionStatus}=FAILED + {@code failureCategory}
 * 是否属于可重试分类、且 未达 attempt 上限推导（与 Knowledge 21.5 RetryPolicy 一致）。具体重试决策由 Knowledge RetryIngestion
 * gRPC 做权威判断。
 *
 * @param docId 文档 ID（十进制字符串）
 * @param knowledgeBaseId 知识库 ID（十进制字符串）
 * @param originalFilename 原始文件名（仅展示）
 * @param fileType 文件类型（TXT / MARKDOWN）
 * @param sizeBytes 字节数
 * @param ingestionStatus 摄取状态（PENDING / PROCESSING / READY / FAILED）
 * @param operationVersion 摄取操作版本
 * @param attempt 当前版本尝试次数
 * @param failureCategory 失败分类（可能为 null 或空字符串）
 * @param failureMessage 安全限长失败描述（可能为 null 或空字符串）
 * @param retryable 是否允许手动重试
 * @param startedAt 当前版本开始时间（RFC 3339 UTC，可能 null）
 * @param completedAt 当前版本完成时间（RFC 3339 UTC，可能 null）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentResponse(
    String docId,
    String knowledgeBaseId,
    String originalFilename,
    String fileType,
    long sizeBytes,
    String ingestionStatus,
    String operationVersion,
    int attempt,
    String failureCategory,
    String failureMessage,
    boolean retryable,
    Instant startedAt,
    Instant completedAt) {}
