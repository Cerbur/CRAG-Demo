package ai.cerbur.crag.console.config.multipart;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Console 文档上传配置（plan_21/21.8）。
 *
 * <p>启用 {@link ConsoleUploadProperties}。multipart resolver 的大小上限通过 {@code
 * spring.servlet.multipart.*} 在 application.yml 配置（与 {@link
 * ConsoleUploadProperties#getMaxFileSizeBytes} 保持一致 10 MiB），让超限请求在 resolver 层先抛 {@code
 * MaxUploadSizeExceededException}（由 GlobalExceptionHandler 映射 41301），与 {@link
 * ai.cerbur.crag.console.document.service.UploadValidation} 双重保险。
 *
 * <p>{@link ConsoleUploadProperties#getDeadlineMillis} 供 {@link
 * ai.cerbur.crag.console.document.service.KnowledgeDocumentClient} 读取，作为上传 gRPC 流式调用 deadline。
 */
@Configuration
@EnableConfigurationProperties(ConsoleUploadProperties.class)
public class ConsoleUploadConfiguration {}
