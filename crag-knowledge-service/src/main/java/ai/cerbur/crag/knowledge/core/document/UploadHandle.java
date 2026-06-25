package ai.cerbur.crag.knowledge.core.document;

import ai.cerbur.crag.knowledge.filestore.TempFileSink;

/**
 * 一次流式上传的有状态句柄，持有 metadata 命令与临时写入槽。
 *
 * <p>由 {@link DocumentUploadService#begin} 创建，调用方按顺序追加字节并在完成或中断时归还给 service。非 Spring Bean。
 */
public final class UploadHandle {

  private final DocumentUploadCommand command;
  private final TempFileSink sink;

  UploadHandle(DocumentUploadCommand command, TempFileSink sink) {
    this.command = command;
    this.sink = sink;
  }

  public DocumentUploadCommand command() {
    return command;
  }

  TempFileSink sink() {
    return sink;
  }
}
