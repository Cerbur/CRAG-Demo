package ai.cerbur.crag.ingestion.producer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.storage.entity.IngestionJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG ingestion 状态事件单元测试（Plan 19）：payload 安全字段与信封组装。Outbox 写入由 {@link
 * RagIngestionStatusEventWriter#buildEnvelope} 校验，真实 outbox_event 持久化由 Docker HTTP 回归证明（plan_19.7）.
 */
@DisplayName("RAG ingestion 状态事件")
@ExtendWith(MockitoExtension.class)
class RagIngestionStatusEventTest {

  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private RagIngestionStatusEventWriter writer;

  private IngestionJob job() {
    return IngestionJob.createPending(7L, 200L, 1001L, 1L, "TXT", 42L, "abc");
  }

  @Nested
  @DisplayName("payload 安全字段")
  class PayloadSecurity {

    @Test
    @DisplayName("序列化 payload 不包含文件内容/storage key/路径/prompt/context/embedding")
    void serializedPayloadExcludesSensitiveFields() {
      RagIngestionStatusPayload payload =
          new RagIngestionStatusPayload(
              7L, 200L, 1001L, 1L, 55L, "FAILED", "FILE_DECODE_FAILED", "bad utf8", 123L);

      String json = objectMapper.writeValueAsString(payload);

      assertThat(json).contains("knowledgeBaseId").contains("failureCategory").contains("status");
      assertThat(json)
          .doesNotContain("storageKey")
          .doesNotContain("storage_key")
          .doesNotContain("path")
          .doesNotContain("content")
          .doesNotContain("prompt")
          .doesNotContain("context")
          .doesNotContain("embedding");
    }
  }

  @Nested
  @DisplayName("信封组装")
  class EnvelopeAssembly {

    @Test
    @DisplayName("PROCESSING 事件信封携带安全字段与正确 resourceType")
    void processingEnvelope() {
      EventEnvelope envelope =
          writer.buildEnvelope(
              job(), RagIngestionStatusEventTypes.INGESTION_PROCESSING, null, null, 77L);

      assertThat(envelope.eventId()).isEqualTo(77L);
      assertThat(envelope.eventType()).isEqualTo("INGESTION_PROCESSING");
      assertThat(envelope.producer()).isEqualTo("rag-service");
      assertThat(envelope.resourceType()).isEqualTo("DOCUMENT");
      assertThat(envelope.resourceId()).isEqualTo(1001L);
      assertThat(envelope.operationVersion()).isEqualTo(1L);
      assertThat(envelope.payload()).contains("\"status\":\"PROCESSING\"");
    }

    @Test
    @DisplayName("FAILED 事件信封携带安全失败分类与摘要")
    void failedEnvelopeCarriesSafeCategory() {
      EventEnvelope envelope =
          writer.buildEnvelope(
              job(),
              RagIngestionStatusEventTypes.INGESTION_FAILED,
              "FILE_DECODE_FAILED",
              "bad utf8",
              78L);

      assertThat(envelope.payload())
          .contains("\"failureCategory\":\"FILE_DECODE_FAILED\"")
          .contains("\"failureMessage\":\"bad utf8\"")
          .contains("\"status\":\"FAILED\"");
    }
  }
}
