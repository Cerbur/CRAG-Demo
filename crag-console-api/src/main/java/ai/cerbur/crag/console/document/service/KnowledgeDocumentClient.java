package ai.cerbur.crag.console.document.service;

import ai.cerbur.crag.console.document.dto.DocumentListResponse;
import ai.cerbur.crag.console.document.dto.DocumentResponse;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.DownstreamTimeoutException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.ForbiddenException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.NotFoundException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.RetryNotAllowedException;
import ai.cerbur.crag.console.document.service.UploadValidation.UploadInvalidException;
import ai.cerbur.crag.console.document.service.UploadValidation.ValidatedUpload;
import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.GetDocumentRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListDocumentsRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListDocumentsResponse;
import ai.cerbur.crag.contracts.knowledge.v1.RetryIngestionRequest;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentRequest;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Knowledge Document gRPC 适配器（plan_21/21.8）。
 *
 * <p>负责 multipart 文件 → gRPC 客户端流式上传的编排、Document 查询/列表、重试。上传时先通过 {@link UploadValidation}
 * 校验文件大小/类型/UTF-8，计算 SHA-256 与 size，再以 metadata-first + bytes chunk 顺序发送 Knowledge。
 *
 * <p>不在数据库事务、日志或异常中保留文件内容；只记录 docId、size、sha256 前缀与状态。非幂等上传与重试不自动重试 gRPC。 所有 ID 在 gRPC 中使用十进制字符串。
 */
@Component
public class KnowledgeDocumentClient {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentClient.class);

  /** 上传分片大小（8 KiB），与 Knowledge 文件读取一致；保证可断言 chunk 顺序。 */
  static final int CHUNK_SIZE = 8192;

  /** 与 Knowledge 21.5 RetryPolicy 一致的可重试失败分类。 */
  static final Set<String> RETRYABLE_CATEGORIES =
      Set.of(
          "DISPATCH_MISSING", "FILE_READ_FAILED", "PROCESSING_TIMEOUT", "INDEX_TRANSIENT_FAILURE");

  /** RetryPolicy 默认总 attempt 上限（首次摄取计为 1）。 */
  static final int MAX_ATTEMPTS = 3;

  private final DocumentServiceGrpc.DocumentServiceStub asyncStub;
  private final DocumentServiceGrpc.DocumentServiceBlockingStub blockingStub;
  private final long deadlineMillis;
  private final int uploadChunkDeadlineMillis;

  @Autowired
  public KnowledgeDocumentClient(
      @Qualifier("consoleKnowledgeChannel") ManagedChannel channel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis,
      @Value("${crag.console.upload.deadline-millis:60000}") int uploadChunkDeadlineMillis) {
    this.asyncStub = DocumentServiceGrpc.newStub(channel);
    this.blockingStub = DocumentServiceGrpc.newBlockingStub(channel);
    this.deadlineMillis = deadlineMillis;
    this.uploadChunkDeadlineMillis = uploadChunkDeadlineMillis;
  }

  /**
   * 上传单个已校验文件到 Knowledge。
   *
   * <p>调用方先通过 {@link UploadValidation#validate} 得到 {@link ValidatedUpload}（含
   * sha256、size、fileType）；本方法 以 metadata-first + bytes chunk 顺序发起客户端流式 UploadDocument。成功返回
   * Knowledge 创建的 Document。
   *
   * @throws UploadInvalidException 大小/类型/UTF-8 不符（调用方应在 validate 阶段拦截；本方法仍做防御性校验）
   */
  public Document upload(ValidatedUpload upload) throws UploadInvalidException {
    UploadValidation.recheckInvariants(upload);
    UploadResponseCollector collector = new UploadResponseCollector();
    StreamObserver<UploadDocumentRequest> requestObserver =
        asyncStub
            .withDeadlineAfter(uploadChunkDeadlineMillis, TimeUnit.MILLISECONDS)
            .uploadDocument(collector);

    // metadata-first
    UploadDocumentMetadata metadata =
        UploadDocumentMetadata.newBuilder()
            .setTenantId(Long.toString(upload.tenantId()))
            .setKnowledgeBaseId(Long.toString(upload.knowledgeBaseId()))
            .setUploadedByUserId(Long.toString(upload.uploadedByUserId()))
            .setOriginalFilename(upload.originalFilename())
            .setFileType(upload.fileType())
            .setSizeBytes(upload.sizeBytes())
            .setSha256(upload.sha256Hex())
            .build();
    requestObserver.onNext(UploadDocumentRequest.newBuilder().setMetadata(metadata).build());

    // bytes chunks 按顺序发送
    try (InputStream input = upload.content()) {
      byte[] buffer = new byte[CHUNK_SIZE];
      int read;
      int chunkIndex = 0;
      while ((read = input.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        requestObserver.onNext(
            UploadDocumentRequest.newBuilder()
                .setChunk(ByteString.copyFrom(buffer, 0, read))
                .build());
        chunkIndex++;
      }
      log.debug(
          "Console 上传分片完成 — kb={} sha256={} chunks={}",
          upload.knowledgeBaseId(),
          maskSha256(upload.sha256Hex()),
          chunkIndex);
    } catch (IOException e) {
      requestObserver.onError(e);
      throw new DownstreamUnavailableException();
    }
    requestObserver.onCompleted();

    collector.awaitResult();
    if (collector.error != null) {
      throw mapUpload(collector.error);
    }
    if (collector.document == null) {
      throw new DownstreamUnavailableException();
    }
    return collector.document;
  }

  /** 列表（Authorize 由 Controller 在调用前完成）。 */
  public DocumentListResponse list(
      long tenantId, long knowledgeBaseId, int pageSize, String pageToken) {
    try {
      ListDocumentsResponse resp =
          blockingStubWithDeadline()
              .listDocuments(
                  ListDocumentsRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      List<DocumentResponse> items = new ArrayList<>();
      for (Document d : resp.getDocumentsList()) {
        items.add(toResponse(d));
      }
      String next = resp.getNextPageToken();
      return new DocumentListResponse(items, next == null || next.isEmpty() ? null : next);
    } catch (StatusRuntimeException e) {
      throw mapDocument(e);
    }
  }

  /** 详情（Authorize 由 Controller 在调用前完成）。 */
  public DocumentResponse get(long tenantId, long knowledgeBaseId, long docId) {
    try {
      Document d =
          blockingStubWithDeadline()
              .getDocument(
                  GetDocumentRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setDocId(Long.toString(docId))
                      .build());
      return toResponse(d);
    } catch (StatusRuntimeException e) {
      throw mapDocument(e);
    }
  }

  /**
   * 手动重试（Authorize 由 Controller 在调用前完成）。
   *
   * <p>权威重试决策由 Knowledge RetryIngestion gRPC 做出（RetryPolicy + CAS）。Knowledge 返回 FAILED_PRECONDITION
   * 表示重试不允许（不可重试分类或 attempt 上限），映射为 40902 INGESTION_RETRY_NOT_ALLOWED。
   */
  public DocumentResponse retry(long actorUserId, long tenantId, long knowledgeBaseId, long docId) {
    try {
      Document d =
          blockingStubWithDeadline()
              .retryIngestion(
                  RetryIngestionRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setDocId(Long.toString(docId))
                      .build());
      return toResponse(d);
    } catch (StatusRuntimeException e) {
      throw mapRetry(e);
    }
  }

  // ---- helpers ----

  private DocumentServiceGrpc.DocumentServiceBlockingStub blockingStubWithDeadline() {
    if (deadlineMillis > 0) {
      return blockingStub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return blockingStub;
  }

  /**
   * 将 Document proto 映射为 DocumentResponse，并推导 {@code retryable}。
   *
   * <p>{@code retryable} = FAILED + 可重试分类 + attempt < MAX_ATTEMPTS。与 Knowledge RetryPolicy
   * 一致；Knowledge RetryIngestion gRPC 做权威二次校验。
   */
  public static DocumentResponse toResponse(Document d) {
    String status = d.getIngestionStatus();
    String failureCategory = d.getFailureCategory();
    int attempt = d.getIngestionAttempt();
    boolean retryable =
        "FAILED".equals(status)
            && failureCategory != null
            && !failureCategory.isBlank()
            && RETRYABLE_CATEGORIES.contains(failureCategory)
            && attempt < MAX_ATTEMPTS;
    return new DocumentResponse(
        d.getDocId(),
        d.getKnowledgeBaseId(),
        d.getOriginalFilename(),
        d.getFileType(),
        d.getSizeBytes(),
        status,
        Long.toString(d.getOperationVersion()),
        attempt,
        nullIfBlank(failureCategory),
        nullIfBlank(d.getFailureMessage()),
        retryable,
        epochMillisToInstant(d.getStartedAtEpochMillis()),
        epochMillisToInstant(d.getCompletedAtEpochMillis()));
  }

  private static String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static Instant epochMillisToInstant(long millis) {
    return millis <= 0 ? null : Instant.ofEpochMilli(millis);
  }

  /** 只暴露 sha256 前 12 字符，避免完整指纹进入日志（防止间接泄漏文件指纹信息）。 */
  private static String maskSha256(String sha256Hex) {
    if (sha256Hex == null || sha256Hex.length() < 12) {
      return "(short)";
    }
    return sha256Hex.substring(0, 12) + "...";
  }

  private static RuntimeException mapUpload(Throwable error) {
    if (error instanceof StatusRuntimeException sre) {
      return mapDocument(sre);
    }
    log.warn("Console 上传失败 — error={}", error.toString());
    return new DownstreamUnavailableException();
  }

  private static RuntimeException mapDocument(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.NOT_FOUND) {
      return new NotFoundException();
    }
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.ALREADY_EXISTS) {
      return new RetryNotAllowedException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new UploadInvalidException("knowledge rejected upload");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    log.warn("Knowledge Document 下游失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  private static RuntimeException mapRetry(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.NOT_FOUND) {
      return new NotFoundException();
    }
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    // FAILED_PRECONDITION：Knowledge RetryPolicy 拒绝（不可重试分类 / attempt 上限 / 非 FAILED）
    if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.ALREADY_EXISTS) {
      return new RetryNotAllowedException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid retry argument");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    log.warn("Knowledge Retry 下游失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  /** 静态工厂：构造 SHA-256 计算器，便于测试复用。 */
  static String sha256Hex(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(data);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** 上传响应收集器：等待 Knowledge 返回 Document 或 error。 */
  private static final class UploadResponseCollector implements StreamObserver<Document> {
    private Document document;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(Document document) {
      this.document = document;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      signalCompleted();
    }

    @Override
    public void onCompleted() {
      signalCompleted();
    }

    private synchronized void signalCompleted() {
      completed = true;
      notifyAll();
    }

    void awaitResult() {
      boolean interrupted = false;
      long deadline = System.currentTimeMillis() + 30000;
      synchronized (this) {
        while (!completed) {
          long remaining = deadline - System.currentTimeMillis();
          if (remaining <= 0) {
            break;
          }
          try {
            wait(remaining);
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** 跨租户或文档不存在 → 404 NOT_FOUND，不泄漏存在性。 */
  public static class NotFoundException extends RuntimeException {
    public NotFoundException() {
      super("not found");
    }
  }

  /** 无上传/查看权限 → 403 FORBIDDEN。 */
  public static class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
      super("forbidden");
    }
  }

  /** 重试不允许 → 40902 INGESTION_RETRY_NOT_ALLOWED。 */
  public static class RetryNotAllowedException extends RuntimeException {
    public RetryNotAllowedException() {
      super("ingestion retry not allowed");
    }
  }

  /** 下游 Knowledge 不可用 → 503 DOWNSTREAM_UNAVAILABLE。 */
  public static class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException() {
      super("downstream unavailable");
    }
  }

  /** 下游 Knowledge 超时 → 504 DOWNSTREAM_TIMEOUT。 */
  public static class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException() {
      super("downstream timeout");
    }
  }
}
