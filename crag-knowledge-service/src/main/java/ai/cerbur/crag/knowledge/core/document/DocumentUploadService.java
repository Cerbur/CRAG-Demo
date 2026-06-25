package ai.cerbur.crag.knowledge.core.document;

import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseNotFoundException;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.FileObjectDao;
import ai.cerbur.crag.knowledge.dao.KnowledgeBaseDao;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.entity.FileObjectEntity;
import ai.cerbur.crag.knowledge.filestore.CompletedUpload;
import ai.cerbur.crag.knowledge.filestore.FileStore;
import ai.cerbur.crag.knowledge.filestore.StorageKeyGenerator;
import ai.cerbur.crag.knowledge.filestore.TempFileSink;
import ai.cerbur.crag.knowledge.producer.DocUploadedOutboxWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单次客户端流式上传核心编排：begin 校验 metadata 与知识库归属并开启临时写入槽，append 流式追加字节，complete 完成 sha256/大小/UTF-8
 * 校验、原子落盘并在同一事务中创建 {@code Document(PENDING)} 与 {@code FileObject(STORED)}。
 *
 * <p>校验或事务失败时不创建业务记录，并清理本次临时或最终文件；落盘后、事务前崩溃产生的孤立文件留给后续 Reconciler。本任务不写 {@code DOC_UPLOADED}
 * Outbox（由 producer 任务接入）。
 */
@Service
public class DocumentUploadService {

  @Autowired private KnowledgeBaseDao knowledgeBaseDao;
  @Autowired private DocumentDao documentDao;
  @Autowired private FileObjectDao fileObjectDao;
  @Autowired private FileStore fileStore;
  @Autowired private StorageKeyGenerator storageKeyGenerator;
  @Autowired private DocumentUploadPolicy uploadPolicy;
  @Autowired private DocUploadedOutboxWriter outboxWriter;

  /** 校验 metadata 与知识库归属，开启临时写入槽。metadata 非法或知识库不存在时抛异常，不创建任何文件。 */
  public UploadHandle begin(DocumentUploadCommand command) {
    uploadPolicy.validateMetadata(command);
    if (knowledgeBaseDao
        .findByIdAndTenant(command.knowledgeBaseId(), command.tenantId())
        .isEmpty()) {
      throw new KnowledgeBaseNotFoundException(command.tenantId(), command.knowledgeBaseId());
    }
    try {
      TempFileSink sink = fileStore.openTempSink();
      return new UploadHandle(command, sink);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to open upload temp file", e);
    }
  }

  /** 流式追加一段字节；同步累积 sha256 与字节数。 */
  public void append(UploadHandle handle, byte[] chunk, int offset, int length) {
    if (length <= 0) {
      return;
    }
    try {
      handle.sink().write(chunk, offset, length);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write upload chunk", e);
    }
  }

  /** 完成上传：固化 sha256/大小，校验内容，原子落盘，同事务创建 Document 与 FileObject。任何失败均清理本次文件并抛出，事务回滚不留下业务记录。 */
  @Transactional
  public DocumentResult complete(UploadHandle handle) {
    CompletedUpload completed = finishQuietlyOnFailure(handle);
    String storageKey = null;
    try {
      uploadPolicy.validateContent(handle.command(), completed);
      storageKey =
          storageKeyGenerator.generate(
              handle.command().tenantId(), handle.command().knowledgeBaseId());
      fileStore.commit(completed.tempPath(), storageKey);
      DocumentEntity doc =
          documentDao.insert(
              DocumentEntity.create(
                  handle.command().knowledgeBaseId(),
                  handle.command().tenantId(),
                  handle.command().uploadedByUserId(),
                  handle.command().originalFilename(),
                  handle.command().fileType().name(),
                  completed.sizeBytes(),
                  completed.sha256()));
      fileObjectDao.insert(
          FileObjectEntity.create(
              doc.getDocId(), storageKey, completed.sizeBytes(), completed.sha256()));
      DocumentResult result =
          new DocumentResult(
              doc.getDocId(),
              handle.command().tenantId(),
              handle.command().knowledgeBaseId(),
              handle.command().uploadedByUserId(),
              handle.command().originalFilename(),
              handle.command().fileType(),
              completed.sizeBytes(),
              completed.sha256(),
              doc.getIngestionStatus(),
              doc.getOperationVersion(),
              DocumentResult.epochMillis(doc.getCreatedAt()),
              DocumentResult.epochMillis(doc.getUpdatedAt()));
      outboxWriter.write(result);
      return result;
    } catch (RuntimeException e) {
      cleanup(completed, storageKey);
      throw e;
    } catch (IOException e) {
      cleanup(completed, storageKey);
      throw new UncheckedIOException("failed to commit upload file", e);
    }
  }

  /** 中断上传：静默清理临时文件。 */
  public void abort(UploadHandle handle) {
    handle.sink().closeQuietly();
  }

  private CompletedUpload finishQuietlyOnFailure(UploadHandle handle) {
    try {
      return handle.sink().finish();
    } catch (IOException e) {
      handle.sink().closeQuietly();
      throw new UncheckedIOException("failed to finalize upload", e);
    }
  }

  private void cleanup(CompletedUpload completed, String storageKey) {
    if (storageKey != null) {
      fileStore.deleteQuietly(storageKey);
    }
    // 临时文件在 commit 成功后已被移动，删除为 best-effort no-op；commit 失败时仍存在。
    try {
      java.nio.file.Files.deleteIfExists(completed.tempPath());
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
