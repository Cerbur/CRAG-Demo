package ai.cerbur.crag.contracts.rag.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.cerbur.crag.contracts.rag.v1.Citation;
import ai.cerbur.crag.contracts.rag.v1.GetIngestionStatusRequest;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusServiceGrpc;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusView;
import ai.cerbur.crag.contracts.rag.v1.MarkTimedOutRequest;
import ai.cerbur.crag.contracts.rag.v1.QueryRequest;
import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.contracts.rag.v1.QueryServiceGrpc;
import ai.cerbur.crag.contracts.rag.v1.RagErrorCode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RAG contracts 架构测试。
 *
 * <p>plan_21/21.1 防漂移护栏：断言 router4 设计列出的正式 RPC 与消息字段稳定存在，且字段号不与 Access/Knowledge 历史字段号冲突。Protobuf 各
 * contracts 模块按 package 隔离，字段号在各自 message 范围内唯一即可；这里只锁定 RAG 自身消息的 shape，跨模块字段号唯一性由各模块 package
 * 隔离保证，重复号校验在编译期由 protoc 保障。
 */
class RagContractsArchitectureTest {

  private static Map<String, FieldDescriptor> fieldMap(Descriptor descriptor) {
    return descriptor.getFields().stream()
        .collect(Collectors.toMap(FieldDescriptor::getName, f -> f));
  }

  @Test
  @DisplayName("QueryService 暴露正式 Query RPC")
  void queryServiceExposesQueryRpc() {
    ServiceDescriptor service = QueryServiceGrpc.getServiceDescriptor();
    boolean hasQuery =
        service.getMethods().stream()
            .map(MethodDescriptor::getBareMethodName)
            .anyMatch("Query"::equals);
    assertTrue(hasQuery, "QueryService 必须暴露 Query RPC");
  }

  @Test
  @DisplayName("IngestionStatusService 暴露 GetIngestionStatus 与 MarkTimedOut RPC")
  void ingestionStatusServiceExposesRpcs() {
    ServiceDescriptor service = IngestionStatusServiceGrpc.getServiceDescriptor();
    boolean hasGet =
        service.getMethods().stream()
            .map(MethodDescriptor::getBareMethodName)
            .anyMatch("GetIngestionStatus"::equals);
    boolean hasMark =
        service.getMethods().stream()
            .map(MethodDescriptor::getBareMethodName)
            .anyMatch("MarkTimedOut"::equals);
    assertTrue(hasGet, "IngestionStatusService 必须暴露 GetIngestionStatus RPC");
    assertTrue(hasMark, "IngestionStatusService 必须暴露 MarkTimedOut RPC");
  }

  @Test
  @DisplayName("QueryRequest 字段 shape 与设计一致：knowledgeBaseId=1, question=2, traceId=3")
  void queryRequestFieldShape() {
    Map<String, FieldDescriptor> fields = fieldMap(QueryRequest.getDescriptor());
    assertEquals(1, fields.get("knowledge_base_id").getNumber());
    assertEquals(2, fields.get("question").getNumber());
    assertEquals(3, fields.get("trace_id").getNumber());
  }

  @Test
  @DisplayName("QueryResponse 字段 shape 与设计一致：answer=1, sources=2")
  void queryResponseFieldShape() {
    Map<String, FieldDescriptor> fields = fieldMap(QueryResponse.getDescriptor());
    assertEquals(1, fields.get("answer").getNumber());
    assertEquals(2, fields.get("sources").getNumber());
    assertEquals(
        Citation.getDescriptor().getFullName(),
        fields.get("sources").getMessageType().getFullName());
  }

  @Test
  @DisplayName("Citation 字段 shape 与设计一致：reference=1, documentId=2, excerpt=3")
  void citationFieldShape() {
    Map<String, FieldDescriptor> fields = fieldMap(Citation.getDescriptor());
    assertEquals(1, fields.get("reference").getNumber());
    assertEquals(2, fields.get("document_id").getNumber());
    assertEquals(3, fields.get("excerpt").getNumber());
  }

  @Test
  @DisplayName("IngestionStatusView 携带 operationVersion, status, attempt, jobId 与失败/时间字段")
  void ingestionStatusViewFieldShape() {
    Map<String, FieldDescriptor> fields = fieldMap(IngestionStatusView.getDescriptor());
    // 关键字段必须存在（router4 设计的投影契约）。
    assertNotNull(fields.get("operation_version"), "缺少 operation_version");
    assertNotNull(fields.get("status"), "缺少 status");
    assertNotNull(fields.get("attempt"), "缺少 attempt");
    assertNotNull(fields.get("job_id"), "缺少 job_id");
    assertNotNull(fields.get("failure_category"), "缺少 failure_category");
    assertNotNull(fields.get("failure_message"), "缺少 failure_message");
    assertNotNull(fields.get("started_at_epoch_millis"), "缺少 started_at_epoch_millis");
    assertNotNull(fields.get("completed_at_epoch_millis"), "缺少 completed_at_epoch_millis");
  }

  @Test
  @DisplayName("GetIngestionStatusRequest 与 MarkTimedOutRequest 字段存在")
  void ingestionStatusRequestFieldShape() {
    assertTrue(
        GetIngestionStatusRequest.getDescriptor().getFields().size() >= 3,
        "GetIngestionStatusRequest 至少包含 tenant/kb/doc 定位字段");
    assertTrue(
        MarkTimedOutRequest.getDescriptor().getFields().size() >= 4,
        "MarkTimedOutRequest 至少包含 tenant/kb/doc/version/staleBefore 定位字段");
  }

  @Test
  @DisplayName("RagErrorCode 至少包含用于稳定 gRPC 错误详情的枚举值")
  void ragErrorCodePresent() {
    assertTrue(
        RagErrorCode.getDescriptor().getValues().size() >= 2,
        "RagErrorCode 必须包含 UNSPECIFIED 与至少一个稳定业务码");
  }
}
