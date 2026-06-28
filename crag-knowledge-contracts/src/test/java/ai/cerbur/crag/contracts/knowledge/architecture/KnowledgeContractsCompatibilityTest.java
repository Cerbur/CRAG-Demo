package ai.cerbur.crag.contracts.knowledge.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.contracts.knowledge.v1.DocumentServiceGrpc;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Knowledge contracts 兼容性架构测试。
 *
 * <p>plan_21/21.1 router4 扩展：Document 在保持 1–12 既有字段号稳定的前提下追加摄取投影与失败/重试字段， 并新增 RetryIngestion RPC，供
 * Console 手动重试和 Knowledge Reconciler 使用。
 */
class KnowledgeContractsCompatibilityTest {

  private static Map<String, FieldDescriptor> fieldMap(Descriptor descriptor) {
    return descriptor.getFields().stream()
        .collect(Collectors.toMap(FieldDescriptor::getName, f -> f));
  }

  private static boolean hasRpc(ServiceDescriptor service, String bareMethodName) {
    return service.getMethods().stream()
        .map(MethodDescriptor::getBareMethodName)
        .anyMatch(bareMethodName::equals);
  }

  @Test
  @DisplayName("DocumentService 追加 RetryIngestion RPC")
  void documentServiceAppendsRetryRpc() {
    ServiceDescriptor service = DocumentServiceGrpc.getServiceDescriptor();
    assertTrue(hasRpc(service, "RetryIngestion"), "缺少 RetryIngestion RPC");
    // 既有 RPC 必须保持。
    assertTrue(hasRpc(service, "UploadDocument"));
    assertTrue(hasRpc(service, "GetDocument"));
    assertTrue(hasRpc(service, "ListDocuments"));
    assertTrue(hasRpc(service, "ReadDocumentFile"));
  }

  @Test
  @DisplayName("Document 保持既有 1–12 字段号稳定并追加摄取投影字段")
  void documentAppendsIngestionProjectionFields() {
    Map<String, FieldDescriptor> fields = fieldMap(Document.getDescriptor());
    // 既有字段号保持稳定。
    assertEquals(1, fields.get("doc_id").getNumber());
    assertEquals(9, fields.get("ingestion_status").getNumber());
    assertEquals(10, fields.get("operation_version").getNumber());
    assertEquals(12, fields.get("updated_at_epoch_millis").getNumber());
    // router4 追加字段必须使用 >= 13。
    assertNotNull(fields.get("ingestion_attempt"), "缺少 ingestion_attempt");
    assertEquals(13, fields.get("ingestion_attempt").getNumber());
    assertNotNull(fields.get("ingestion_job_id"), "缺少 ingestion_job_id");
    assertEquals(14, fields.get("ingestion_job_id").getNumber());
    assertNotNull(fields.get("failure_category"), "缺少 failure_category");
    assertEquals(15, fields.get("failure_category").getNumber());
    assertNotNull(fields.get("failure_message"), "缺少 failure_message");
    assertEquals(16, fields.get("failure_message").getNumber());
    assertNotNull(fields.get("started_at_epoch_millis"), "缺少 started_at_epoch_millis");
    assertEquals(17, fields.get("started_at_epoch_millis").getNumber());
    assertNotNull(fields.get("completed_at_epoch_millis"), "缺少 completed_at_epoch_millis");
    assertEquals(18, fields.get("completed_at_epoch_millis").getNumber());
    assertNotNull(fields.get("next_retry_at_epoch_millis"), "缺少 next_retry_at_epoch_millis");
    assertEquals(19, fields.get("next_retry_at_epoch_millis").getNumber());
  }
}
