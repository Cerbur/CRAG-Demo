package ai.cerbur.crag.retrieval.rerank.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SidecarRerankClient 单元测试 —— 验证输入保护逻辑.
 *
 * <p>RestClient HTTP 调用部分需集成测试覆盖，单测聚焦参数校验.
 *
 * @since 2026-06-15
 */
@DisplayName("SidecarRerankClient Sidecar Rerank 客户端")
class SidecarRerankClientTest {

  private final SidecarRerankClient client = new SidecarRerankClient();

  /** 手动构造的客户端实例，未经过 Spring @PostConstruct 初始化 RestClient， 因此仅测试 rerank 方法的输入保护（不依赖 sidecar 连接）. */
  @Nested
  @DisplayName("输入保护")
  class InputProtection {

    @Test
    @DisplayName("query 为 null → 返回空列表")
    void nullQueryReturnsEmpty() {
      List<RerankClient.RerankResult> results = client.rerank(null, List.of("文档1", "文档2"));

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("query 为空字符串 → 返回空列表")
    void blankQueryReturnsEmpty() {
      List<RerankClient.RerankResult> results = client.rerank("", List.of("文档1"));

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("query 为纯空白 → 返回空列表")
    void whitespaceQueryReturnsEmpty() {
      List<RerankClient.RerankResult> results = client.rerank("   \t\n  ", List.of("文档1"));

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("documents 为 null → 返回空列表")
    void nullDocumentsReturnsEmpty() {
      List<RerankClient.RerankResult> results = client.rerank("测试问题", null);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("documents 为空列表 → 返回空列表")
    void emptyDocumentsReturnsEmpty() {
      List<RerankClient.RerankResult> results = client.rerank("测试问题", Collections.emptyList());

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("query 和 documents 均为 null → 返回空列表")
    void bothNullReturnsEmpty() {
      List<RerankClient.RerankResult> results = client.rerank(null, null);

      assertThat(results).isEmpty();
    }
  }
}
