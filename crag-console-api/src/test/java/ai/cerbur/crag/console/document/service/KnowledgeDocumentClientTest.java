package ai.cerbur.crag.console.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.console.document.dto.DocumentListResponse;
import ai.cerbur.crag.console.document.dto.DocumentResponse;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.ForbiddenException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.NotFoundException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.RetryNotAllowedException;
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
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KnowledgeDocumentClient 进程内 gRPC 组件测试（plan_21/21.8）。
 *
 * <p>验证：上传 metadata-first + bytes chunk 顺序、SHA-256 与 size 强校验、list/get/retry 映射、status 投影 retryable
 * 推导。真实跨服务由 21.13 Docker 全链路证明。
 */
@DisplayName("KnowledgeDocumentClient in-process gRPC")
class KnowledgeDocumentClientTest {

  private Server server;
  private ManagedChannel channel;
  private FakeDocumentService fake;
  private KnowledgeDocumentClient client;

  @BeforeEach
  void setUp() throws IOException {
    fake = new FakeDocumentService();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(fake).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new KnowledgeDocumentClient(channel, 5000L, 10000);
  }

  @AfterEach
  void tearDown() {
    if (channel != null && !channel.isShutdown()) channel.shutdownNow();
    if (server != null && !server.isShutdown()) server.shutdownNow();
  }

  @Test
  @DisplayName("upload：metadata-first，bytes chunk 顺序，size/sha256 强校验")
  void uploadStreamsMetadataFirstThenChunks() throws Exception {
    byte[] content = repeat("hello world\n", 1000).getBytes(StandardCharsets.UTF_8);
    String expectedSha = sha256Hex(content);
    ValidatedUpload upload =
        new ValidatedUpload(
            1L,
            100L,
            123L,
            "note.txt",
            "TXT",
            content.length,
            expectedSha,
            new ByteArrayInputStream(content));
    fake.uploadResponse = docProto(200L, 100L, "note.txt", "TXT", content.length, "PENDING", 1L);

    Document result = client.upload(upload);

    assertThat(result.getDocId()).isEqualTo("200");
    // metadata-first：第一条请求是 metadata
    assertThat(fake.receivedRequests).isNotEmpty();
    assertThat(fake.receivedRequests.get(0).getRequestCase())
        .isEqualTo(UploadDocumentRequest.RequestCase.METADATA);
    UploadDocumentMetadata md = fake.receivedRequests.get(0).getMetadata();
    assertThat(md.getSha256()).isEqualTo(expectedSha);
    assertThat(md.getSizeBytes()).isEqualTo(content.length);
    assertThat(md.getFileType()).isEqualTo("TXT");
    assertThat(md.getKnowledgeBaseId()).isEqualTo("100");
    assertThat(md.getUploadedByUserId()).isEqualTo("123");
    // 后续均为 chunk
    long chunkCount =
        fake.receivedRequests.stream()
            .filter(r -> r.getRequestCase() == UploadDocumentRequest.RequestCase.CHUNK)
            .count();
    int expectedChunks =
        (int) Math.ceil(content.length / (double) KnowledgeDocumentClient.CHUNK_SIZE);
    assertThat(chunkCount).isEqualTo(expectedChunks);
    // 重构的字节与原内容一致（顺序正确）
    ByteString reassembled = reassembleChunks(fake.receivedRequests);
    assertThat(reassembled.toByteArray()).isEqualTo(content);
  }

  @Test
  @DisplayName("upload Knowledge INVALID_ARGUMENT → UploadInvalidException")
  void uploadInvalidArgumentMaps() {
    ValidatedUpload upload = simpleUpload("data".getBytes(StandardCharsets.UTF_8));
    fake.uploadStatus = Status.INVALID_ARGUMENT;

    assertThatThrownBy(() -> client.upload(upload))
        .isInstanceOf(UploadValidation.UploadInvalidException.class);
  }

  @Test
  @DisplayName("upload Knowledge UNAVAILABLE → DownstreamUnavailableException")
  void uploadUnavailableMaps() {
    ValidatedUpload upload = simpleUpload("data".getBytes(StandardCharsets.UTF_8));
    fake.uploadStatus = Status.UNAVAILABLE;

    assertThatThrownBy(() -> client.upload(upload))
        .isInstanceOf(DownstreamUnavailableException.class);
  }

  @Test
  @DisplayName("list 返回分页 items + nextPageToken")
  void listMaps() {
    fake.listResponse =
        ListDocumentsResponse.newBuilder()
            .addDocuments(docProto(200L, 100L, "a.txt", "TXT", 10, "PENDING", 1L))
            .addDocuments(docProto(201L, 100L, "b.md", "MARKDOWN", 20, "READY", 1L))
            .setNextPageToken("201")
            .build();

    DocumentListResponse resp = client.list(1L, 100L, 20, "");

    assertThat(resp.items()).hasSize(2);
    assertThat(resp.items().get(0).docId()).isEqualTo("200");
    assertThat(resp.items().get(0).ingestionStatus()).isEqualTo("PENDING");
    assertThat(resp.items().get(1).ingestionStatus()).isEqualTo("READY");
    assertThat(resp.nextPageToken()).isEqualTo("201");
  }

  @Test
  @DisplayName("list NOT_FOUND → NotFoundException（跨租户不泄漏）")
  void listNotFoundMaps() {
    fake.listStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> client.list(1L, 999L, 20, "")).isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("list PERMISSION_DENIED → ForbiddenException")
  void listForbiddenMaps() {
    fake.listStatus = Status.PERMISSION_DENIED;

    assertThatThrownBy(() -> client.list(1L, 100L, 20, "")).isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("get 返回完整摄取投影")
  void getMaps() {
    fake.getResponse = docProto(200L, 100L, "a.txt", "TXT", 10, "PENDING", 1L);

    DocumentResponse resp = client.get(1L, 100L, 200L);

    assertThat(resp.docId()).isEqualTo("200");
    assertThat(resp.knowledgeBaseId()).isEqualTo("100");
    assertThat(resp.fileType()).isEqualTo("TXT");
    assertThat(resp.sizeBytes()).isEqualTo(10);
    assertThat(resp.ingestionStatus()).isEqualTo("PENDING");
    assertThat(resp.operationVersion()).isEqualTo("1");
    assertThat(resp.attempt()).isEqualTo(1);
  }

  @Test
  @DisplayName("get NOT_FOUND → NotFoundException")
  void getNotFoundMaps() {
    fake.getStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> client.get(1L, 100L, 999L)).isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("retry 成功 → 返回新版本 DocumentResponse")
  void retryMaps() {
    fake.retryResponse = docProto(200L, 100L, "a.txt", "TXT", 10, "PENDING", 2L);

    DocumentResponse resp = client.retry(123L, 1L, 100L, 200L);

    assertThat(resp.operationVersion()).isEqualTo("2");
    assertThat(fake.retryRequest.getDocId()).isEqualTo("200");
    assertThat(fake.retryRequest.getActorUserId()).isEqualTo("123");
  }

  @Test
  @DisplayName("retry FAILED_PRECONDITION → RetryNotAllowedException（attempt 上限或不可重试分类）")
  void retryNotAllowedMaps() {
    fake.retryStatus = Status.FAILED_PRECONDITION;

    assertThatThrownBy(() -> client.retry(123L, 1L, 100L, 200L))
        .isInstanceOf(RetryNotAllowedException.class);
  }

  @Test
  @DisplayName("retry NOT_FOUND → NotFoundException")
  void retryNotFoundMaps() {
    fake.retryStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> client.retry(123L, 1L, 100L, 999L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("status 投影：FAILED + DISPATCH_MISSING + attempt 1 → retryable=true")
  void statusProjectionFailedRetryable() {
    Document d =
        docProtoFull(
            200L,
            100L,
            "a.txt",
            "TXT",
            10,
            "FAILED",
            1L,
            1,
            "DISPATCH_MISSING",
            "transient",
            null,
            null);
    DocumentResponse resp = KnowledgeDocumentClient.toResponse(d);
    assertThat(resp.retryable()).isTrue();
    assertThat(resp.failureCategory()).isEqualTo("DISPATCH_MISSING");
  }

  @Test
  @DisplayName("status 投影：FAILED + UTF8_DECODE_FAILED（不可重试分类）→ retryable=false")
  void statusProjectionNonRetryableCategory() {
    Document d =
        docProtoFull(
            200L,
            100L,
            "a.txt",
            "TXT",
            10,
            "FAILED",
            1L,
            1,
            "UTF8_DECODE_FAILED",
            "decode",
            null,
            null);
    DocumentResponse resp = KnowledgeDocumentClient.toResponse(d);
    assertThat(resp.retryable()).isFalse();
  }

  @Test
  @DisplayName("status 投影：FAILED + DISPATCH_MISSING + attempt=3（上限）→ retryable=false")
  void statusProjectionAttemptLimit() {
    Document d =
        docProtoFull(
            200L,
            100L,
            "a.txt",
            "TXT",
            10,
            "FAILED",
            1L,
            3,
            "DISPATCH_MISSING",
            "transient",
            null,
            null);
    DocumentResponse resp = KnowledgeDocumentClient.toResponse(d);
    assertThat(resp.retryable()).isFalse();
  }

  @Test
  @DisplayName("status 投影：READY → retryable=false")
  void statusProjectionReadyNotRetryable() {
    Document d = docProto(200L, 100L, "a.txt", "TXT", 10, "READY", 1L);
    DocumentResponse resp = KnowledgeDocumentClient.toResponse(d);
    assertThat(resp.retryable()).isFalse();
  }

  // ---- helpers / fakes ----

  private ValidatedUpload simpleUpload(byte[] content) {
    return new ValidatedUpload(
        1L,
        100L,
        123L,
        "a.txt",
        "TXT",
        content.length,
        sha256Hex(content),
        new ByteArrayInputStream(content));
  }

  private static ByteString reassembleChunks(List<UploadDocumentRequest> requests) {
    ByteString.Output out = ByteString.newOutput();
    try {
      for (UploadDocumentRequest r : requests) {
        if (r.getRequestCase() == UploadDocumentRequest.RequestCase.CHUNK) {
          out.write(r.getChunk().toByteArray());
        }
      }
      return out.toByteString();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static String repeat(String s, int times) {
    StringBuilder sb = new StringBuilder(s.length() * times);
    for (int i = 0; i < times; i++) sb.append(s);
    return sb.toString();
  }

  private static String sha256Hex(byte[] data) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(data);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Document docProto(
      long docId,
      long kbId,
      String filename,
      String fileType,
      long size,
      String status,
      long version) {
    return docProtoFull(docId, kbId, filename, fileType, size, status, version, 1, "", "", 0L, 0L);
  }

  private static Document docProtoFull(
      long docId,
      long kbId,
      String filename,
      String fileType,
      long size,
      String status,
      long version,
      int attempt,
      String failureCategory,
      String failureMessage,
      Long startedAt,
      Long completedAt) {
    return Document.newBuilder()
        .setDocId(Long.toString(docId))
        .setKnowledgeBaseId(Long.toString(kbId))
        .setOriginalFilename(filename)
        .setFileType(fileType)
        .setSizeBytes(size)
        .setIngestionStatus(status)
        .setOperationVersion(version)
        .setIngestionAttempt(attempt)
        .setFailureCategory(failureCategory)
        .setFailureMessage(failureMessage)
        .setStartedAtEpochMillis(startedAt == null ? 0L : startedAt)
        .setCompletedAtEpochMillis(completedAt == null ? 0L : completedAt)
        .build();
  }

  static class FakeDocumentService extends DocumentServiceGrpc.DocumentServiceImplBase {
    Status uploadStatus = Status.OK;
    Document uploadResponse = Document.getDefaultInstance();
    final List<UploadDocumentRequest> receivedRequests = new ArrayList<>();

    Status listStatus = Status.OK;
    ListDocumentsResponse listResponse = ListDocumentsResponse.getDefaultInstance();

    Status getStatus = Status.OK;
    Document getResponse = Document.getDefaultInstance();

    Status retryStatus = Status.OK;
    Document retryResponse = Document.getDefaultInstance();
    RetryIngestionRequest retryRequest;

    @Override
    public StreamObserver<UploadDocumentRequest> uploadDocument(
        StreamObserver<Document> responseObserver) {
      return new StreamObserver<>() {
        @Override
        public void onNext(UploadDocumentRequest request) {
          receivedRequests.add(request);
        }

        @Override
        public void onError(Throwable error) {
          // client aborted
        }

        @Override
        public void onCompleted() {
          if (uploadStatus != Status.OK) {
            responseObserver.onError(uploadStatus.asRuntimeException());
            return;
          }
          responseObserver.onNext(uploadResponse);
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void listDocuments(
        ListDocumentsRequest request, StreamObserver<ListDocumentsResponse> resp) {
      if (listStatus != Status.OK) {
        resp.onError(listStatus.asRuntimeException());
        return;
      }
      resp.onNext(listResponse);
      resp.onCompleted();
    }

    @Override
    public void getDocument(GetDocumentRequest request, StreamObserver<Document> resp) {
      if (getStatus != Status.OK) {
        resp.onError(getStatus.asRuntimeException());
        return;
      }
      resp.onNext(getResponse);
      resp.onCompleted();
    }

    @Override
    public void retryIngestion(RetryIngestionRequest request, StreamObserver<Document> resp) {
      retryRequest = request;
      if (retryStatus != Status.OK) {
        resp.onError(retryStatus.asRuntimeException());
        return;
      }
      resp.onNext(retryResponse);
      resp.onCompleted();
    }
  }
}
