package ai.cerbur.crag.ingestion.knowledge;

import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileChunk;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.ReadDocumentFileRequest;
import ai.cerbur.crag.grpc.runtime.client.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通过 Knowledge gRPC {@code ReadDocumentFile} 流式读取文档文件（Plan 19）.
 *
 * <p>只依赖 {@code crag-knowledge-contracts} 与 gRPC runtime，不依赖 Knowledge service 实现。调用方身份与 service
 * token 由 {@link GrpcChannelFactory} 的拦截器自动附加（{@code crag.grpc.client.caller-service} / {@code
 * crag.grpc.client.token}）。 读取为同步阻塞：服务端流的首条消息携带安全元数据（sizeBytes / sha256 /
 * fileType），后续消息为字节块，拼接为原始字节内容.
 *
 * <p>失败（gRPC 错误、缺失元数据、超时）抛出 {@link KnowledgeFileReadException}，由编排映射为 {@code
 * FILE_READ_FAILED}；异常消息不 透传文件内容、storage key 或路径.
 */
@Component
public class KnowledgeDocumentFileClient {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentFileClient.class);

  private final GrpcChannelFactory channelFactory;
  private final String knowledgeTarget;
  private final long deadlineMillis;

  // 单线程消费场景下惰性复用 channel，避免每次读取新建/泄漏 channel。
  private volatile ManagedChannel channel;

  @Autowired
  public KnowledgeDocumentFileClient(
      GrpcChannelFactory channelFactory,
      @Value("${crag.rag.ingestion.knowledge.target:knowledge-service:9092}")
          String knowledgeTarget,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.channelFactory = channelFactory;
    this.knowledgeTarget = knowledgeTarget;
    this.deadlineMillis = deadlineMillis;
  }

  /** 测试可注入已构造 channel，跳过 channelFactory. */
  KnowledgeDocumentFileClient(ManagedChannel channel, long deadlineMillis) {
    this.channelFactory = null;
    this.knowledgeTarget = null;
    this.deadlineMillis = deadlineMillis;
    this.channel = channel;
  }

  /**
   * 读取文档文件.
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @return 文件元数据与原始字节
   * @throws KnowledgeFileReadException gRPC 读取失败、缺失元数据或超时
   */
  public KnowledgeFileRead read(long tenantId, long knowledgeBaseId, long docId) {
    ManagedChannel ch = resolveChannel();
    DocumentServiceGrpc.DocumentServiceStub stub =
        DocumentServiceGrpc.newStub(ch).withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    ReadDocumentFileRequest request =
        ReadDocumentFileRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
            .setDocId(Long.toString(docId))
            .build();

    StreamAssembler assembler = new StreamAssembler(docId, deadlineMillis);
    stub.readDocumentFile(request, assembler);
    return assembler.await();
  }

  private ManagedChannel resolveChannel() {
    ManagedChannel existing = channel;
    if (existing != null && !existing.isShutdown()) {
      return existing;
    }
    synchronized (this) {
      if (channel == null || channel.isShutdown()) {
        if (channelFactory == null) {
          throw new KnowledgeFileReadException("no channel available for knowledge file read");
        }
        channel = channelFactory.create("knowledge-service", knowledgeTarget, true);
      }
      return channel;
    }
  }

  /** 同步装配服务端流：首条元数据 + 后续字节块. */
  private static final class StreamAssembler implements StreamObserver<DocumentFileChunk> {
    private final long docId;
    private final long awaitMillis;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private DocumentFileMetadata metadata;
    private Throwable error;

    StreamAssembler(long docId, long deadlineMillis) {
      this.docId = docId;
      this.awaitMillis = deadlineMillis + 1000;
    }

    @Override
    public void onNext(DocumentFileChunk chunk) {
      switch (chunk.getPayloadCase()) {
        case METADATA -> this.metadata = chunk.getMetadata();
        case DATA -> {
          try {
            buffer.write(chunk.getData().toByteArray());
          } catch (RuntimeException | java.io.IOException e) {
            error = e;
          }
        }
        default -> {}
      }
    }

    @Override
    public void onError(Throwable t) {
      error = t;
      latch.countDown();
    }

    @Override
    public void onCompleted() {
      latch.countDown();
    }

    KnowledgeFileRead await() {
      try {
        if (!latch.await(awaitMillis, TimeUnit.MILLISECONDS)) {
          throw new KnowledgeFileReadException("knowledge file read timed out — docId=" + docId);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new KnowledgeFileReadException("knowledge file read interrupted — docId=" + docId, e);
      }
      if (error != null) {
        String reason =
            (error instanceof StatusRuntimeException sre)
                ? sre.getStatus().getCode().name()
                : "read error";
        throw new KnowledgeFileReadException(
            "knowledge file read failed — docId=" + docId + " reason=" + reason, error);
      }
      if (metadata == null) {
        throw new KnowledgeFileReadException(
            "knowledge file read returned no metadata — docId=" + docId);
      }
      byte[] content = buffer.toByteArray();
      return new KnowledgeFileRead(
          metadata.getSizeBytes(), metadata.getSha256(), metadata.getFileType(), content);
    }
  }
}
