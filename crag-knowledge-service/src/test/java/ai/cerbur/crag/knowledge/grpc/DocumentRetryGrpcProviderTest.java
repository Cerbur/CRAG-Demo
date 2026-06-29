package ai.cerbur.crag.knowledge.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.contracts.knowledge.v1.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.contracts.knowledge.v1.RetryIngestionRequest;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.UploadDocumentRequest;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.grpc.provider.DocumentGrpcProvider;
import ai.cerbur.crag.knowledge.grpc.provider.KnowledgeBaseGrpcProvider;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * DocumentGrpcProvider.retryIngestion 真实 provider 组件测试（plan_21/21.5 验收退回修复）。
 *
 * <p>直接驱动真实的 {@link DocumentGrpcProvider} Bean（非 Fake/非桩），覆盖真实 {@code retryIngestion} override →
 * {@code IngestionRetryService.retry} → CAS DAO → H2 链路，并经真实 {@code GrpcErrorMapper} 验证 gRPC 错误映射。与
 * 同模块既有 {@code GrpcProviderComponentTest} 一致，断言 provider 行为而非 gRPC 传输成帧。验证：
 *
 * <ul>
 *   <li>可重试 FAILED 文档 retry 返回新 operationVersion、status=PENDING、attempt+1；
 *   <li>非 FAILED 文档 retry 经 {@link GrpcErrorMapper} 映射为 {@link Status.Code#FAILED_PRECONDITION}。
 * </ul>
 *
 * <p>类级 {@code @Transactional} 为 {@code applyIngestionProjection}（{@code @Modifying}）与 retry CAS
 * 提供事务。H2 仅证明 provider/DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明。
 */
@DisplayName("DocumentGrpcProvider RetryIngestion real provider")
@SpringBootTest
@Transactional
class DocumentRetryGrpcProviderTest {

  private static final AtomicLong TENANT_SEQ = new AtomicLong(19000L);

  @DynamicPropertySource
  static void filestoreRoot(DynamicPropertyRegistry registry) throws IOException {
    Path dir = Files.createTempDirectory("knowledge-retry-grpc-test");
    registry.add("crag.knowledge.filestore.root", dir::toString);
    registry.add("spring.sql.init.mode", () -> "always");
    registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-knowledge.sql");
  }

  @Autowired private DocumentGrpcProvider documentProvider;
  @Autowired private KnowledgeBaseGrpcProvider knowledgeBaseProvider;
  @Autowired private DocumentDao documentDao;

  @Test
  @DisplayName("可重试 FAILED 文档 retry 返回新版本 PENDING")
  void retryReturnsNewVersion() {
    long tenant = uniqueTenant();
    long kb = createKnowledgeBase(tenant, "retry-kb");
    DocumentEntity doc = uploadDocument(tenant, kb, "retry-doc.txt", "router4 retry content");
    markFailed(doc, 1, "INDEX_TRANSIENT_FAILURE");
    long previousOpVersion = doc.getOperationVersion();

    Document resp = retry(tenant, kb, doc.getDocId());

    assertThat(resp.getIngestionStatus()).isEqualTo("PENDING");
    assertThat(resp.getOperationVersion()).isEqualTo(previousOpVersion + 1);
    assertThat(resp.getIngestionAttempt()).isEqualTo(2);
    assertThat(resp.getFailureCategory()).isEmpty();
  }

  @Test
  @DisplayName("非 FAILED 文档 retry 返回 FAILED_PRECONDITION")
  void retryOnNonFailedReturnsFailedPrecondition() {
    long tenant = uniqueTenant();
    long kb = createKnowledgeBase(tenant, "retry-kb");
    // 上传后文档保持 PENDING（未达 FAILED），retry 必须被拒绝。
    DocumentEntity doc = uploadDocument(tenant, kb, "pending-doc.txt", "still pending");

    assertThatThrownBy(() -> retryExpectingError(tenant, kb, doc.getDocId()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  // ---- helpers ----

  private Document retry(long tenant, long kb, long docId) {
    TestObserver<Document> observer = new TestObserver<>();
    documentProvider.retryIngestion(retryRequest(tenant, kb, docId), observer);
    assertThat(observer.error).as("retry 不应失败").isNull();
    assertThat(observer.values).hasSize(1);
    return observer.values.get(0);
  }

  private void retryExpectingError(long tenant, long kb, long docId) {
    TestObserver<Document> observer = new TestObserver<>();
    documentProvider.retryIngestion(retryRequest(tenant, kb, docId), observer);
    if (observer.error == null) {
      throw new AssertionError(
          "expected FAILED_PRECONDITION but retry succeeded: " + observer.values);
    }
    if (observer.error instanceof RuntimeException re) {
      throw re;
    }
    throw new AssertionError(observer.error);
  }

  private static RetryIngestionRequest retryRequest(long tenant, long kb, long docId) {
    return RetryIngestionRequest.newBuilder()
        .setActorUserId("1")
        .setTenantId(Long.toString(tenant))
        .setKnowledgeBaseId(Long.toString(kb))
        .setDocId(Long.toString(docId))
        .build();
  }

  private long createKnowledgeBase(long tenant, String name) {
    TestObserver<ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBase> observer =
        new TestObserver<>();
    knowledgeBaseProvider.createKnowledgeBase(
        CreateKnowledgeBaseRequest.newBuilder()
            .setTenantId(Long.toString(tenant))
            .setCreatedByUserId("1")
            .setName(name)
            .build(),
        observer);
    assertThat(observer.error).as("createKnowledgeBase 不应失败").isNull();
    return Long.parseLong(observer.values.get(0).getKnowledgeBaseId());
  }

  private DocumentEntity uploadDocument(long tenant, long kb, String filename, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    TestObserver<Document> observer = new TestObserver<>();
    StreamObserver<UploadDocumentRequest> uploader = documentProvider.uploadDocument(observer);
    uploader.onNext(
        UploadDocumentRequest.newBuilder()
            .setMetadata(
                UploadDocumentMetadata.newBuilder()
                    .setTenantId(Long.toString(tenant))
                    .setKnowledgeBaseId(Long.toString(kb))
                    .setUploadedByUserId("1")
                    .setOriginalFilename(filename)
                    .setFileType("TXT")
                    .setSizeBytes(bytes.length)
                    .setSha256(sha256(bytes))
                    .build())
            .build());
    uploader.onNext(
        UploadDocumentRequest.newBuilder().setChunk(ByteString.copyFrom(bytes)).build());
    uploader.onCompleted();
    assertThat(observer.error).as("upload 不应失败").isNull();
    String docId = observer.values.get(0).getDocId();
    return documentDao.findByDocIdAndTenant(Long.parseLong(docId), tenant).orElseThrow();
  }

  private void markFailed(DocumentEntity doc, int attempt, String category) {
    documentDao.applyIngestionProjection(
        doc.getDocId(),
        doc.getTenantId(),
        doc.getKnowledgeBaseId(),
        doc.getOperationVersion(),
        doc.getVersion(),
        "FAILED",
        attempt,
        7001L,
        category,
        "router4 simulated failure",
        LocalDateTime.now().minusMinutes(5),
        LocalDateTime.now(),
        null);
  }

  private static long uniqueTenant() {
    return TENANT_SEQ.incrementAndGet();
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
