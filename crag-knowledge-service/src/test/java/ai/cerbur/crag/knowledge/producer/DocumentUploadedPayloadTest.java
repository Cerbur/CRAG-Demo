package ai.cerbur.crag.knowledge.producer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** DOC_UPLOADED payload 纯单元测试：序列化只含安全字段，不含路径、storage key 或文件内容。 */
@DisplayName("DocumentUploadedPayload")
class DocumentUploadedPayloadTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("序列化包含全部安全字段")
  void serializedPayloadContainsSafeFields() {
    DocumentUploadedPayload payload =
        new DocumentUploadedPayload(1L, 10L, 100L, 1L, "TXT", 5L, "abc123");

    String json = objectMapper.writeValueAsString(payload);

    assertThat(json)
        .contains(
            "tenantId",
            "knowledgeBaseId",
            "docId",
            "operationVersion",
            "fileType",
            "sizeBytes",
            "sha256");
  }

  @Test
  @DisplayName("序列化不含路径、storage key 或文件内容")
  void serializedPayloadHasNoSensitiveFields() {
    DocumentUploadedPayload payload =
        new DocumentUploadedPayload(1L, 10L, 100L, 1L, "TXT", 5L, "abc123");

    String json = objectMapper.writeValueAsString(payload);

    assertThat(json).doesNotContain("storageKey", "path", "content", "originalFilename");
  }
}
