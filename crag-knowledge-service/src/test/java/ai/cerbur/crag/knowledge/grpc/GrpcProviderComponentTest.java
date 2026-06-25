package ai.cerbur.crag.knowledge.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.contracts.knowledge.v1.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileChunk;
import ai.cerbur.crag.contracts.knowledge.v1.GetDocumentRequest;
import ai.cerbur.crag.contracts.knowledge.v1.GetKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBase;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesResponse;
import ai.cerbur.crag.contracts.knowledge.v1.ReadDocumentFileRequest;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentRequest;
import ai.cerbur.crag.knowledge.grpc.provider.DocumentGrpcProvider;
import ai.cerbur.crag.knowledge.grpc.provider.KnowledgeBaseGrpcProvider;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Knowledge gRPC provider 组件测试：H2 + 真实 filestore，直接驱动 provider 方法，覆盖 KnowledgeBase 创建/查询/列表、
 * Document 流式上传/查询/读取的正常路径与非法参数、sha256 mismatch、读取不存在文档。
 */
@SpringBootTest
@DisplayName("Knowledge gRPC providers")
class GrpcProviderComponentTest {

  private static final AtomicLong TENANT_SEQ = new AtomicLong(9000L);

  @DynamicPropertySource
  static void filestoreRoot(DynamicPropertyRegistry registry) throws IOException {
    Path dir = Files.createTempDirectory("knowledge-grpc-test");
    registry.add("crag.knowledge.filestore.root", dir::toString);
    registry.add("spring.sql.init.mode", () -> "always");
    registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-knowledge.sql");
  }

  @Autowired private KnowledgeBaseGrpcProvider knowledgeBaseProvider;
  @Autowired private DocumentGrpcProvider documentProvider;

  @Test
  @DisplayName("KnowledgeBase 创建、查询、列表正常")
  void knowledgeBaseCreateGetList() {
    long tenant = uniqueTenant();
    long knowledgeBaseId = createKnowledgeBase(tenant, "my-kb");

    KnowledgeBase fetched =
        single(
            KnowledgeBase.class,
            observer ->
                knowledgeBaseProvider.getKnowledgeBase(
                    GetKnowledgeBaseRequest.newBuilder()
                        .setTenantId(Long.toString(tenant))
                        .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                        .build(),
                    observer));
    assertThat(fetched.getName()).isEqualTo("my-kb");
    assertThat(fetched.getStatus()).isEqualTo("ACTIVE");

    ListKnowledgeBasesResponse list =
        single(
            ListKnowledgeBasesResponse.class,
            observer ->
                knowledgeBaseProvider.listKnowledgeBases(
                    ListKnowledgeBasesRequest.newBuilder()
                        .setTenantId(Long.toString(tenant))
                        .setPageSize(10)
                        .build(),
                    observer));
    assertThat(list.getKnowledgeBasesCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("Document 流式上传、查询与读取回环成功，读取不含 storage key")
  void documentUploadGetAndRead() {
    long tenant = uniqueTenant();
    long knowledgeBaseId = createKnowledgeBase(tenant, "docs");
    byte[] content = "hello grpc upload".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(content);

    TestObserver<Document> uploadObserver = new TestObserver<>();
    StreamObserver<UploadDocumentRequest> uploader =
        documentProvider.uploadDocument(uploadObserver);
    uploader.onNext(
        UploadDocumentRequest.newBuilder()
            .setMetadata(
                UploadDocumentMetadata.newBuilder()
                    .setTenantId(Long.toString(tenant))
                    .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                    .setUploadedByUserId("1")
                    .setOriginalFilename("doc.txt")
                    .setFileType("TXT")
                    .setSizeBytes(content.length)
                    .setSha256(sha256)
                    .build())
            .build());
    uploader.onNext(
        UploadDocumentRequest.newBuilder().setChunk(ByteString.copyFrom(content)).build());
    uploader.onCompleted();

    assertThat(uploadObserver.error).isNull();
    Document uploaded = uploadObserver.values.get(0);
    assertThat(uploaded.getIngestionStatus()).isEqualTo("PENDING");
    assertThat(uploaded.getSha256()).isEqualTo(sha256);
    String docId = uploaded.getDocId();

    Document fetched =
        single(
            Document.class,
            observer ->
                documentProvider.getDocument(
                    GetDocumentRequest.newBuilder()
                        .setTenantId(Long.toString(tenant))
                        .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                        .setDocId(docId)
                        .build(),
                    observer));
    assertThat(fetched.getSha256()).isEqualTo(sha256);

    TestObserver<DocumentFileChunk> readObserver = new TestObserver<>();
    documentProvider.readDocumentFile(
        ReadDocumentFileRequest.newBuilder()
            .setTenantId(Long.toString(tenant))
            .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
            .setDocId(docId)
            .build(),
        readObserver);
    assertThat(readObserver.error).isNull();
    ByteString data = ByteString.EMPTY;
    for (DocumentFileChunk chunk : readObserver.values) {
      assertThat(chunk.getPayloadCase())
          .isNotEqualTo(DocumentFileChunk.PayloadCase.PAYLOAD_NOT_SET);
      if (chunk.getPayloadCase() == DocumentFileChunk.PayloadCase.DATA) {
        data = data.concat(chunk.getData());
      }
    }
    assertThat(data.toByteArray()).isEqualTo(content);
  }

  @Test
  @DisplayName("非法扩展名上传返回 INVALID_ARGUMENT")
  void uploadRejectsBadExtension() {
    long tenant = uniqueTenant();
    long knowledgeBaseId = createKnowledgeBase(tenant, "docs");
    TestObserver<Document> uploadObserver = new TestObserver<>();
    StreamObserver<UploadDocumentRequest> uploader =
        documentProvider.uploadDocument(uploadObserver);
    uploader.onNext(
        UploadDocumentRequest.newBuilder()
            .setMetadata(
                UploadDocumentMetadata.newBuilder()
                    .setTenantId(Long.toString(tenant))
                    .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                    .setUploadedByUserId("1")
                    .setOriginalFilename("payload.exe")
                    .setFileType("TXT")
                    .setSizeBytes(1)
                    .setSha256(sha256("x".getBytes(StandardCharsets.UTF_8)))
                    .build())
            .build());
    uploader.onCompleted();

    assertThat(uploadObserver.error).isNotNull();
    assertThat(Status.fromThrowable(uploadObserver.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("sha256 不匹配上传返回 INVALID_ARGUMENT")
  void uploadRejectsSha256Mismatch() {
    long tenant = uniqueTenant();
    long knowledgeBaseId = createKnowledgeBase(tenant, "docs");
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    TestObserver<Document> uploadObserver = new TestObserver<>();
    StreamObserver<UploadDocumentRequest> uploader =
        documentProvider.uploadDocument(uploadObserver);
    uploader.onNext(
        UploadDocumentRequest.newBuilder()
            .setMetadata(
                UploadDocumentMetadata.newBuilder()
                    .setTenantId(Long.toString(tenant))
                    .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                    .setUploadedByUserId("1")
                    .setOriginalFilename("doc.txt")
                    .setFileType("TXT")
                    .setSizeBytes(content.length)
                    .setSha256("0".repeat(64))
                    .build())
            .build());
    uploader.onNext(
        UploadDocumentRequest.newBuilder().setChunk(ByteString.copyFrom(content)).build());
    uploader.onCompleted();

    assertThat(uploadObserver.error).isNotNull();
    assertThat(Status.fromThrowable(uploadObserver.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("读取不存在文档返回 NOT_FOUND")
  void readNonexistentDocumentReturnsNotFound() {
    long tenant = uniqueTenant();
    TestObserver<DocumentFileChunk> readObserver = new TestObserver<>();
    documentProvider.readDocumentFile(
        ReadDocumentFileRequest.newBuilder()
            .setTenantId(Long.toString(tenant))
            .setKnowledgeBaseId("1")
            .setDocId("999999")
            .build(),
        readObserver);
    assertThat(readObserver.error).isNotNull();
    assertThat(Status.fromThrowable(readObserver.error).getCode()).isEqualTo(Status.Code.NOT_FOUND);
  }

  private long createKnowledgeBase(long tenant, String name) {
    TestObserver<KnowledgeBase> observer = new TestObserver<>();
    knowledgeBaseProvider.createKnowledgeBase(
        CreateKnowledgeBaseRequest.newBuilder()
            .setTenantId(Long.toString(tenant))
            .setCreatedByUserId("1")
            .setName(name)
            .build(),
        observer);
    assertThat(observer.error).isNull();
    return Long.parseLong(observer.values.get(0).getKnowledgeBaseId());
  }

  private static long uniqueTenant() {
    return TENANT_SEQ.incrementAndGet();
  }

  /** 单结果期望的便捷封装：执行一次 unary 调用并断言无错，返回唯一响应。 */
  private <T> T single(Class<T> type, java.util.function.Consumer<StreamObserver<T>> call) {
    TestObserver<T> observer = new TestObserver<>();
    call.accept(observer);
    assertThat(observer.error).as("unexpected gRPC error").isNull();
    assertThat(observer.values).hasSize(1);
    return observer.values.get(0);
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class TestObserver<T> implements StreamObserver<T> {
    final List<T> values = new ArrayList<>();
    Throwable error;

    @Override
    public void onNext(T value) {
      values.add(value);
    }

    @Override
    public void onError(Throwable t) {
      error = t;
    }

    @Override
    public void onCompleted() {}
  }
}
