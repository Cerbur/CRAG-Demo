package ai.cerbur.crag.knowledge.core.file;

import ai.cerbur.crag.knowledge.core.document.FileType;
import java.io.InputStream;

/**
 * 文件读取句柄，携带安全 metadata 与内容流。
 *
 * <p>禁止包含 storage key 或文件路径；调用方读完内容后必须关闭 {@link #content()}。
 */
public record FileRead(
    long docId,
    long tenantId,
    long knowledgeBaseId,
    FileType fileType,
    long sizeBytes,
    String sha256,
    InputStream content) {}
