package ai.cerbur.crag.ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DocUploadedPayload 解析与校验单元测试（Plan 19）.
 *
 * <p>验证合法 payload 解析成功，缺失/类型错误/非法值抛出 {@link InvalidDocUploadedPayloadException}，且异常消息不泄漏文件内容.
 */
@DisplayName("DocUploadedPayload 解析与校验")
class DocUploadedPayloadTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private String payload(String body) {
    return switch (body) {
      case "valid" ->
          """
          {"tenantId":7,"knowledgeBaseId":200,"docId":1001,"operationVersion":1,
           "fileType":"TXT","sizeBytes":42,"sha256":"abc123"}
          """;
      default -> body;
    };
  }

  @Test
  @DisplayName("合法 payload → 解析为对应字段")
  void validPayloadParses() {
    DocUploadedPayload parsed = DocUploadedPayload.parse(payload("valid"), objectMapper);

    assertThat(parsed.tenantId()).isEqualTo(7L);
    assertThat(parsed.knowledgeBaseId()).isEqualTo(200L);
    assertThat(parsed.docId()).isEqualTo(1001L);
    assertThat(parsed.operationVersion()).isEqualTo(1L);
    assertThat(parsed.fileType()).isEqualTo("TXT");
    assertThat(parsed.sizeBytes()).isEqualTo(42L);
    assertThat(parsed.sha256()).isEqualTo("abc123");
  }

  @Test
  @DisplayName("MARKDOWN fileType 同样被接受")
  void markdownFileTypeAccepted() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", 1).put("knowledgeBaseId", 2).put("docId", 3).put("operationVersion", 1);
    node.put("fileType", "MARKDOWN").put("sizeBytes", 10).put("sha256", "def");

    DocUploadedPayload parsed =
        DocUploadedPayload.parse(objectMapper.writeValueAsString(node), objectMapper);

    assertThat(parsed.fileType()).isEqualTo("MARKDOWN");
  }

  @Nested
  @DisplayName("非法 payload → 抛 InvalidDocUploadedPayloadException")
  class Invalid {

    @Test
    @DisplayName("非 JSON 字符串 → 解析失败")
    void notJsonThrows() {
      assertThatThrownBy(() -> DocUploadedPayload.parse("not json", objectMapper))
          .isInstanceOf(InvalidDocUploadedPayloadException.class);
    }

    @Test
    @DisplayName("缺字段 → 失败")
    void missingFieldThrows() {
      assertThatThrownBy(
              () ->
                  DocUploadedPayload.parse(
                      """
                  {"tenantId":7,"knowledgeBaseId":200,"docId":1001,"operationVersion":1,
                   "fileType":"TXT","sizeBytes":42}
                  """,
                      objectMapper))
          .isInstanceOf(InvalidDocUploadedPayloadException.class)
          .hasMessageContaining("sha256");
    }

    @Test
    @DisplayName("数值字段为字符串 → 失败")
    void numericAsStringThrows() {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("tenantId", "7")
          .put("knowledgeBaseId", 200)
          .put("docId", 1001)
          .put("operationVersion", 1);
      node.put("fileType", "TXT").put("sizeBytes", 42).put("sha256", "abc");

      assertThatThrownBy(
              () -> DocUploadedPayload.parse(objectMapper.writeValueAsString(node), objectMapper))
          .isInstanceOf(InvalidDocUploadedPayloadException.class)
          .hasMessageContaining("tenantId");
    }

    @Test
    @DisplayName("sizeBytes 非正 → 失败")
    void nonPositiveSizeThrows() {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("tenantId", 1).put("knowledgeBaseId", 2).put("docId", 3).put("operationVersion", 1);
      node.put("fileType", "TXT").put("sizeBytes", 0).put("sha256", "abc");

      assertThatThrownBy(
              () -> DocUploadedPayload.parse(objectMapper.writeValueAsString(node), objectMapper))
          .isInstanceOf(InvalidDocUploadedPayloadException.class)
          .hasMessageContaining("sizeBytes");
    }

    @Test
    @DisplayName("不支持的 fileType → 失败")
    void unsupportedFileTypeThrows() {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("tenantId", 1).put("knowledgeBaseId", 2).put("docId", 3).put("operationVersion", 1);
      node.put("fileType", "PDF").put("sizeBytes", 5).put("sha256", "abc");

      assertThatThrownBy(
              () -> DocUploadedPayload.parse(objectMapper.writeValueAsString(node), objectMapper))
          .isInstanceOf(InvalidDocUploadedPayloadException.class)
          .hasMessageContaining("fileType");
    }
  }
}
