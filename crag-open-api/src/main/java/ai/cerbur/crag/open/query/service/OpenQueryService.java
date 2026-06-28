package ai.cerbur.crag.open.query.service;

import ai.cerbur.crag.open.auth.service.OpenApiKeyAuthService;
import ai.cerbur.crag.open.authcache.CachedApiKey;
import ai.cerbur.crag.open.query.dto.CitationResponse;
import ai.cerbur.crag.open.query.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Open Query 编排（plan_21/21.10）。
 *
 * <p>流程：API Key 鉴权 → RAG Query → source 映射。LLM RPC 不自动重试（由 {@link RagQueryClient} 保证）。 sources 只暴露
 * reference / documentId / excerpt，已做 500 字符防御截断。
 */
@Component
public class OpenQueryService {

  @Autowired private OpenApiKeyAuthService authService;
  @Autowired private RagQueryClient ragClient;

  /**
   * 执行查询。
   *
   * @param completeKey 完整 API Key
   * @param question 用户问题（已校验）
   * @param traceId trace ID
   */
  public QueryResponse query(String completeKey, String question, String traceId) {
    CachedApiKey identity = authService.authenticate(completeKey);
    RagQueryClient.QueryResult result =
        ragClient.query(identity.knowledgeBaseId(), question, traceId);
    java.util.List<CitationResponse> sources = new java.util.ArrayList<>();
    for (RagQueryClient.QuerySource s : result.sources()) {
      sources.add(new CitationResponse(s.reference(), s.documentId(), s.excerpt()));
    }
    return new QueryResponse(result.answer(), sources);
  }
}
