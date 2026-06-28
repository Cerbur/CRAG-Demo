package ai.cerbur.crag.open.query.service;

import ai.cerbur.crag.contracts.rag.v1.Citation;
import ai.cerbur.crag.contracts.rag.v1.QueryRequest;
import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.contracts.rag.v1.QueryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG Query gRPC 适配器（plan_21/21.10）。
 *
 * <p>封装 {@code Query} 调用，将稳定 gRPC Status 映射为 Open 业务异常。LLM 不可用映射为 {@link
 * LlmUnavailableException}（50201），不自动重试（LLM RPC 不重试）。
 */
@Component
public class RagQueryClient {

  private static final Logger log = LoggerFactory.getLogger(RagQueryClient.class);

  /** source excerpt 防御截断上限（Unicode 字符）。 */
  static final int EXCERPT_MAX_CHARS = 500;

  private final ManagedChannel channel;
  private final QueryServiceGrpc.QueryServiceBlockingStub stub;
  private final long deadlineMillis;

  @Autowired
  public RagQueryClient(
      @Qualifier("openRagChannel") ManagedChannel channel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.channel = channel;
    this.stub = QueryServiceGrpc.newBlockingStub(channel);
    this.deadlineMillis = deadlineMillis;
  }

  /**
   * 调用 RAG Query。
   *
   * @param knowledgeBaseId 知识库 ID（由 Key 决定）
   * @param question 用户问题（已校验）
   * @param traceId trace ID
   */
  public QueryResult query(long knowledgeBaseId, String question, String traceId) {
    try {
      QueryResponse resp =
          stub()
              .query(
                  QueryRequest.newBuilder()
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setQuestion(question)
                      .setTraceId(traceId == null ? "" : traceId)
                      .build());
      List<QuerySource> sources = new ArrayList<>();
      for (Citation c : resp.getSourcesList()) {
        sources.add(new QuerySource(c.getReference(), c.getDocumentId(), truncate(c.getExcerpt())));
      }
      return new QueryResult(resp.getAnswer(), sources);
    } catch (StatusRuntimeException e) {
      throw mapStatus(e);
    }
  }

  // ---- helpers ----

  private QueryServiceGrpc.QueryServiceBlockingStub stub() {
    if (deadlineMillis > 0) {
      return stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return stub;
  }

  /** 防御截断：超过 500 Unicode 字符截断。 */
  static String truncate(String excerpt) {
    if (excerpt == null) {
      return "";
    }
    return excerpt.length() <= EXCERPT_MAX_CHARS
        ? excerpt
        : excerpt.substring(0, EXCERPT_MAX_CHARS);
  }

  private static RuntimeException mapStatus(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new InvalidQueryException();
    }
    if (code == Status.Code.NOT_FOUND) {
      return new KnowledgeBaseNotFoundException();
    }
    if (code == Status.Code.UNAVAILABLE) {
      // RAG 将 LLM 不可用映射为 UNAVAILABLE；HTTP 端映射为 50201 LLM_UNAVAILABLE
      return new LlmUnavailableException();
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    log.warn("RAG Query 下游调用失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  /** Query 结果。sources 已做 excerpt 防御截断。 */
  public record QueryResult(String answer, List<QuerySource> sources) {
    public QueryResult {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }

  /** source 引用：reference + documentId + excerpt（不暴露 chunk id / 分数）。 */
  public record QuerySource(String reference, String documentId, String excerpt) {}

  /** 查询参数非法；映射为 40002。 */
  public static class InvalidQueryException extends RuntimeException {
    public InvalidQueryException() {
      super("invalid query");
    }
  }

  /** KnowledgeBase 不存在；映射为 40401，不泄漏存在性。 */
  public static class KnowledgeBaseNotFoundException extends RuntimeException {
    public KnowledgeBaseNotFoundException() {
      super("knowledge base not found");
    }
  }

  /** LLM 不可用；映射为 50201。 */
  public static class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException() {
      super("llm unavailable");
    }
  }

  /** 下游 RAG 不可用；映射为 50301。 */
  public static class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException() {
      super("downstream unavailable");
    }
  }

  /** 下游 RAG 超时；映射为 50401。 */
  public static class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException() {
      super("downstream timeout");
    }
  }
}
