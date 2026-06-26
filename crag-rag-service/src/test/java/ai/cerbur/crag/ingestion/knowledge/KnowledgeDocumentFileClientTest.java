package ai.cerbur.crag.ingestion.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileChunk;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentFileMetadata;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.ReadDocumentFileRequest;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KnowledgeDocumentFileClient 组件测试（Plan 19）：进程内 gRPC 验证服务端流装配（元数据 + 字节块）与失败映射.
 *
 * <p>真实跨服务 gRPC（Knowledge 服务端 + 调用方身份）由 Docker HTTP 回归证明（plan_19.7）.
 */
@DisplayName("KnowledgeDocumentFileClient gRPC 流式读取")
class KnowledgeDocumentFileClientTest {

  private Server server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdownNow();
    }
    if (server != null && !server.isShutdown()) {
      server.shutdownNow();
    }
  }

  private KnowledgeDocumentFileClient startClient(FakeDocumentService service) throws IOException {
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new KnowledgeDocumentFileClient(channel, 5000L);
  }

  @Test
  @DisplayName("正常流 → 装配元数据与拼接后的字节内容")
  void readAssemblesMetadataAndBytes() throws IOException {
    KnowledgeDocumentFileClient client =
        startClient(
            new FakeDocumentService(metadata(11, "sha-abc", "TXT"), "hello world".getBytes()));

    KnowledgeFileRead read = client.read(7L, 200L, 1001L);

    assertThat(read.sizeBytes()).isEqualTo(11);
    assertThat(read.sha256()).isEqualTo("sha-abc");
    assertThat(read.fileType()).isEqualTo("TXT");
    assertThat(new String(read.content())).isEqualTo("hello world");
  }

  @Test
  @DisplayName("gRPC 错误 → KnowledgeFileReadException")
  void readErrorThrows() throws IOException {
    KnowledgeDocumentFileClient client =
        startClient(new FakeDocumentService(Status.NOT_FOUND.withDescription("not found")));

    assertThatThrownBy(() -> client.read(7L, 200L, 1001L))
        .isInstanceOf(KnowledgeFileReadException.class)
        .hasMessageContaining("NOT_FOUND");
  }

  @Test
  @DisplayName("缺失元数据 → KnowledgeFileReadException")
  void missingMetadataThrows() throws IOException {
    KnowledgeDocumentFileClient client =
        startClient(new FakeDocumentService("only-data".getBytes()));

    assertThatThrownBy(() -> client.read(7L, 200L, 1001L))
        .isInstanceOf(KnowledgeFileReadException.class)
        .hasMessageContaining("no metadata");
  }

  private static DocumentFileMetadata metadata(long size, String sha256, String fileType) {
    return DocumentFileMetadata.newBuilder()
        .setSizeBytes(size)
        .setSha256(sha256)
        .setFileType(fileType)
        .build();
  }

  /** 进程内 DocumentService 替身：按构造参数响应 ReadDocumentFile. */
  static final class FakeDocumentService extends DocumentServiceGrpc.DocumentServiceImplBase {
    private final DocumentFileMetadata metadata;
    private final byte[] data;
    private final Status error;

    FakeDocumentService(DocumentFileMetadata metadata, byte[] data) {
      this.metadata = metadata;
      this.data = data;
      this.error = null;
    }

    FakeDocumentService(byte[] dataOnly) {
      this.metadata = null;
      this.data = dataOnly;
      this.error = null;
    }

    FakeDocumentService(Status error) {
      this.metadata = null;
      this.data = null;
      this.error = error;
    }

    @Override
    public void readDocumentFile(
        ReadDocumentFileRequest request, StreamObserver<DocumentFileChunk> responseObserver) {
      if (error != null) {
        responseObserver.onError(error.asRuntimeException());
        return;
      }
      if (metadata != null) {
        responseObserver.onNext(DocumentFileChunk.newBuilder().setMetadata(metadata).build());
      }
      if (data != null) {
        responseObserver.onNext(
            DocumentFileChunk.newBuilder().setData(ByteString.copyFrom(data)).build());
      }
      responseObserver.onCompleted();
    }
  }
}
