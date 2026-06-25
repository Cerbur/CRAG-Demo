package ai.cerbur.crag.knowledge.filestore;

import java.nio.file.Path;

/** 流式接收完成后的不可变快照：临时文件路径、实际字节数与实际 sha256（十六进制小写）。 */
public record CompletedUpload(Path tempPath, long sizeBytes, String sha256) {}
