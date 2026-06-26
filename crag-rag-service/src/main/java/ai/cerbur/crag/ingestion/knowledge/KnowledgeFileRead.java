package ai.cerbur.crag.ingestion.knowledge;

/**
 * Knowledge gRPC {@code ReadDocumentFile} 读取结果（Plan 19）.
 *
 * <p>携带首条消息的安全元数据（sizeBytes / sha256 / fileType）与拼接后的原始字节内容。内容仅留在内存用于 UTF-8 解码与切分， 不持久化原始字节、storage
 * key 或路径.
 *
 * @param sizeBytes 文件字节数（来自 Knowledge 元数据）
 * @param sha256 文件 sha256（来自 Knowledge 元数据）
 * @param fileType 文件类型展示值（来自 Knowledge 元数据）
 * @param content 文件原始字节
 */
public record KnowledgeFileRead(long sizeBytes, String sha256, String fileType, byte[] content) {}
