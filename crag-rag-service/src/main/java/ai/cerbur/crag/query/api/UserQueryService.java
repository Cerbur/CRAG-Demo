package ai.cerbur.crag.query.api;

import ai.cerbur.crag.query.context.ContextBuilder;
import ai.cerbur.crag.query.context.NonceSourceBoundaryFactory;
import ai.cerbur.crag.query.context.QueryContext;
import ai.cerbur.crag.query.context.SourceBoundaryFactory;
import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.contract.LlmClient;
import ai.cerbur.crag.query.llm.contract.LlmProviderException;
import ai.cerbur.crag.query.llm.contract.LlmRequest;
import ai.cerbur.crag.query.llm.contract.LlmResult;
import ai.cerbur.crag.query.llm.contract.LlmUsage;
import ai.cerbur.crag.query.prompt.PromptBuilder;
import ai.cerbur.crag.query.reference.ReferenceAnalysis;
import ai.cerbur.crag.query.reference.ReferenceAnalyzer;
import ai.cerbur.crag.retrieval.api.RetrievalService;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户查询服务 —— 编排混合检索 + LLM 生成全链路.
 *
 * <p>流水线步骤：
 *
 * <ol>
 *   <li>Trim 并校验查询（1-2000 字符）
 *   <li>调用 {@link RetrievalService#retrieveEvidence(String, int)} 获取证据
 *   <li>通过 {@link ContextBuilder} 在字符预算内构建上下文
 *   <li>空上下文 → 直接返回"知识库证据不足"（不调用 LLM）
 *   <li>通过 {@link PromptBuilder} 构造 LLM 请求
 *   <li>调用 {@link LlmClient#generate(LlmRequest)}
 *   <li>通过 {@link ReferenceAnalyzer} 分析回答中的引用
 *   <li>返回 {@link UserQueryResult}
 * </ol>
 *
 * <p>日志涵盖 MDC requestId、结构化 INFO 行与 DEBUG 级完整细节，不记录 context/prompt/thinking 及认证凭据.
 */
@Service
public class UserQueryService {

  private static final Logger log = LoggerFactory.getLogger(UserQueryService.class);

  static final int MAX_QUESTION_LENGTH = 2000;
  static final String INSUFFICIENT_EVIDENCE_ANSWER = "知识库证据不足";

  @Autowired private RetrievalService retrievalService;
  @Autowired private ContextBuilder contextBuilder;
  @Autowired private PromptBuilder promptBuilder;
  @Autowired private LlmClient llmClient;
  @Autowired private QueryProperties queryProperties;
  @Autowired private ReferenceAnalyzer referenceAnalyzer;

  /**
   * 执行用户查询全链路（限定知识库）.
   *
   * @param knowledgeBaseId 知识库 ID（强隔离键）
   * @param question 用户问题
   * @return 查询结果
   * @throws InvalidQueryException 查询为空或超长
   * @throws LlmUnavailableException LLM 调用失败
   * @throws RuntimeException 检索内部错误
   */
  public UserQueryResult answer(long knowledgeBaseId, String question) {
    return answerWithEvidence(knowledgeBaseId, question).result();
  }

  /**
   * 执行用户查询全链路并返回完整产出（Plan 21.4）—— gRPC Provider 据此映射 Citation（reference/documentId/excerpt）
   * 而无需重复检索.
   *
   * @param knowledgeBaseId 知识库 ID（强隔离键）
   * @param question 用户问题
   * @return 完整产出，含查询结果与对应 parent evidence
   * @throws InvalidQueryException 查询为空或超长
   * @throws LlmUnavailableException LLM 调用失败
   * @throws RuntimeException 检索内部错误
   */
  public UserQueryOutcome answerWithEvidence(long knowledgeBaseId, String question) {
    return runQuery(knowledgeBaseId, question);
  }

  private UserQueryOutcome runQuery(long knowledgeBaseId, String question) {
    Instant start = Instant.now();

    // MDC requestId — reuse existing or generate
    String originalRequestId = MDC.get("requestId");
    String requestId =
        (originalRequestId != null && !originalRequestId.isBlank())
            ? originalRequestId
            : UUID.randomUUID().toString();
    MDC.put("requestId", requestId);

    try {
      // 1. Trim
      String trimmed = question != null ? question.trim() : null;

      // 2. Validate blank
      if (trimmed == null || trimmed.isBlank()) {
        throw new InvalidQueryException(
            InvalidQueryException.Reason.QUESTION_REQUIRED, "Question must not be blank");
      }

      // 3. Validate length
      if (trimmed.length() > MAX_QUESTION_LENGTH) {
        throw new InvalidQueryException(
            InvalidQueryException.Reason.QUESTION_TOO_LONG,
            "Question must not exceed "
                + MAX_QUESTION_LENGTH
                + " characters, got "
                + trimmed.length());
      }

      // Configuration
      int topN = queryProperties.getRetrieval().topN();
      int maxCharacters = queryProperties.getContext().maxCharacters();
      String provider = resolveProviderName();
      String protocol = resolveProtocol();
      String model = resolveModel();

      // 4. Retrieve evidence
      List<ParentEvidenceResult> evidence;
      try {
        evidence = retrievalService.retrieveEvidence(knowledgeBaseId, trimmed, topN);
      } catch (Exception e) {
        log.error("requestId={} Retrieval service failed", requestId, e);
        throw new RuntimeException("Retrieval service failed", e);
      }

      boolean evidenceWasEmpty = evidence == null || evidence.isEmpty();

      // Create boundary factory from evidence (needs content for collision checking)
      List<ParentEvidenceResult> safeEvidence =
          evidence != null ? evidence : Collections.emptyList();
      SourceBoundaryFactory boundaryFactory = new NonceSourceBoundaryFactory(safeEvidence);

      // 5. Build context
      QueryContext context;
      try {
        context = contextBuilder.build(safeEvidence, maxCharacters, boundaryFactory);
      } catch (Exception e) {
        log.error("requestId={} Context builder failed", requestId, e);
        throw new RuntimeException("Context builder failed", e);
      }

      // 6. Empty context — no LLM call
      if (context.contextText().isEmpty()) {
        int retrievedCount = safeEvidence.size();
        int includedCount = 0;
        long dupeSkipped = 0;
        long budgetSkipped = 0;

        if (!evidenceWasEmpty) {
          long uniqueParents =
              safeEvidence.stream().map(ParentEvidenceResult::parentChunkId).distinct().count();
          dupeSkipped = safeEvidence.size() - uniqueParents;
          budgetSkipped = uniqueParents;
        }

        long elapsed = Duration.between(start, Instant.now()).toMillis();
        log.info(
            "requestId={} provider={} protocol={} model={} "
                + "questionChars={} retrieved={} included={} dupeSkipped={} budgetSkipped={} "
                + "contextChars={} totalRefs={} validRefs={} validSrcs={} invalidRefs={} "
                + "unrefSrcs={} usageAvailable={} "
                + "elapsedMs={} result={}",
            requestId,
            provider,
            protocol,
            model,
            trimmed.length(),
            retrievedCount,
            includedCount,
            dupeSkipped,
            budgetSkipped,
            0,
            0,
            0,
            0,
            Collections.emptyList(),
            0,
            false,
            elapsed,
            evidenceWasEmpty ? "retrieval_empty" : "context_budget_empty");

        return new UserQueryOutcome(
            new UserQueryResult(INSUFFICIENT_EVIDENCE_ANSWER, List.of()), List.of());
      }

      // 7. Build prompt
      LlmRequest llmRequest = promptBuilder.build(trimmed, context);

      // 8. Call LLM
      LlmResult llmResult;
      try {
        llmResult = llmClient.generate(llmRequest);
      } catch (LlmProviderException e) {
        log.warn("requestId={} LLM provider failed: category={}", requestId, e.getCategory());

        int retrievedCount = safeEvidence.size();
        int includedCount = context.sources().size();
        long uniqueParents =
            safeEvidence.stream().map(ParentEvidenceResult::parentChunkId).distinct().count();
        long dupeSkipped = safeEvidence.size() - uniqueParents;
        long budgetSkipped = uniqueParents - includedCount;

        long elapsed = Duration.between(start, Instant.now()).toMillis();
        log.info(
            "requestId={} provider={} protocol={} model={} "
                + "questionChars={} retrieved={} included={} dupeSkipped={} budgetSkipped={} "
                + "contextChars={} totalRefs={} validRefs={} validSrcs={} invalidRefs={} "
                + "unrefSrcs={} usageAvailable={} "
                + "elapsedMs={} result={}",
            requestId,
            provider,
            protocol,
            model,
            trimmed.length(),
            retrievedCount,
            includedCount,
            dupeSkipped,
            budgetSkipped,
            context.characterCount(),
            0,
            0,
            0,
            Collections.emptyList(),
            0,
            false,
            elapsed,
            "llm_unavailable");

        throw new LlmUnavailableException("LLM provider failure: " + e.getCategory(), e, provider);
      }

      // 9. Reference analysis
      ReferenceAnalysis refAnalysis;
      try {
        refAnalysis = referenceAnalyzer.analyze(llmResult.answer(), context.sources().size());
      } catch (Exception e) {
        log.warn(
            "requestId={} Reference analysis failed, using default all-zero counts", requestId, e);
        refAnalysis = new ReferenceAnalysis(0, 0, 0, java.util.List.of(), 0);
      }

      // 10. Build result
      UserQueryResult result = new UserQueryResult(llmResult.answer(), context.sources());

      // 11. Log — INFO structured
      long elapsed = Duration.between(start, Instant.now()).toMillis();
      LlmUsage usage = llmResult.usage();
      boolean usageAvailable = usage != null;

      int retrievedCount = safeEvidence.size();
      int includedCount = context.sources().size();
      long uniqueParents =
          safeEvidence.stream().map(ParentEvidenceResult::parentChunkId).distinct().count();
      long dupeSkipped = safeEvidence.size() - uniqueParents;
      long budgetSkipped = uniqueParents - includedCount;

      if (usageAvailable) {
        log.info(
            "requestId={} provider={} protocol={} model={} "
                + "questionChars={} retrieved={} included={} dupeSkipped={} budgetSkipped={} "
                + "contextChars={} totalRefs={} validRefs={} validSrcs={} invalidRefs={} "
                + "unrefSrcs={} usageAvailable={} inputTokens={} outputTokens={} thinkingTokens={} "
                + "elapsedMs={} result={}",
            requestId,
            provider,
            protocol,
            model,
            trimmed.length(),
            retrievedCount,
            includedCount,
            dupeSkipped,
            budgetSkipped,
            context.characterCount(),
            refAnalysis.totalOccurrences(),
            refAnalysis.validOccurrences(),
            refAnalysis.validSourceCount(),
            refAnalysis.invalidReferences(),
            refAnalysis.unreferencedSourceCount(),
            true,
            usage.inputTokens(),
            usage.outputTokens(),
            usage.thinkingTokens(),
            elapsed,
            "success");
      } else {
        log.info(
            "requestId={} provider={} protocol={} model={} "
                + "questionChars={} retrieved={} included={} dupeSkipped={} budgetSkipped={} "
                + "contextChars={} totalRefs={} validRefs={} validSrcs={} invalidRefs={} "
                + "unrefSrcs={} usageAvailable={} "
                + "elapsedMs={} result={}",
            requestId,
            provider,
            protocol,
            model,
            trimmed.length(),
            retrievedCount,
            includedCount,
            dupeSkipped,
            budgetSkipped,
            context.characterCount(),
            refAnalysis.totalOccurrences(),
            refAnalysis.validOccurrences(),
            refAnalysis.validSourceCount(),
            refAnalysis.invalidReferences(),
            refAnalysis.unreferencedSourceCount(),
            false,
            elapsed,
            "success");
      }

      // 12. Log — DEBUG full detail
      if (log.isDebugEnabled()) {
        log.debug(
            "requestId={} question={} answer={} sourceMap={} validSourceMap={} invalidRefs={} "
                + "skippedParents={}",
            requestId,
            sanitizeForLog(trimmed),
            sanitizeForLog(llmResult.answer()),
            buildSourceMap(context.sources()),
            buildValidSourceMap(context.sources()),
            refAnalysis.invalidReferences(),
            buildSkippedParents(safeEvidence, context.sources()));
      }

      // 13. Build evidence slice aligned with sources (only parents that made it into Context)
      List<ParentEvidenceResult> includedEvidence = selectIncludedEvidence(safeEvidence, result);

      return new UserQueryOutcome(result, includedEvidence);

    } finally {
      // Restore MDC requestId
      if (originalRequestId == null || originalRequestId.isBlank()) {
        MDC.remove("requestId");
      } else {
        MDC.put("requestId", originalRequestId);
      }
    }
  }

  /**
   * 按 {@code result.sources()} 的 parentChunkId 顺序从 evidence 中选出实际进入 Context 的 parent（Plan 21.4）.
   *
   * <p>用于 gRPC Provider 映射 Citation（reference/documentId/excerpt）.
   */
  private static List<ParentEvidenceResult> selectIncludedEvidence(
      List<ParentEvidenceResult> evidence, UserQueryResult result) {
    Set<Long> includedParentIds = new HashSet<>();
    for (QuerySource s : result.sources()) {
      includedParentIds.add(s.parentChunkId());
    }
    List<ParentEvidenceResult> included = new ArrayList<>();
    for (ParentEvidenceResult pe : evidence) {
      if (includedParentIds.contains(pe.parentChunkId())) {
        included.add(pe);
      }
    }
    return included;
  }

  // ============================================================
  // Internal helpers
  // ============================================================

  /** 转义日志中的特殊字符以防止日志注入. */
  static String sanitizeForLog(String value) {
    if (value == null) {
      return null;
    }
    return value.replace("\r", "\\r").replace("\n", "\\n");
  }

  /** 构建完整的 source 映射字符串用于 DEBUG 日志. */
  static String buildSourceMap(List<QuerySource> sources) {
    if (sources == null || sources.isEmpty()) {
      return "";
    }
    return sources.stream()
        .map(s -> s.reference() + "→" + s.parentChunkId() + "→" + s.matchedChildIds())
        .collect(Collectors.joining(", "));
  }

  /** 构建有效 source 映射字符串用于 DEBUG 日志. */
  static String buildValidSourceMap(List<QuerySource> sources) {
    if (sources == null || sources.isEmpty()) {
      return "";
    }
    return sources.stream()
        .map(s -> s.reference() + "→" + s.parentChunkId())
        .collect(Collectors.joining(", "));
  }

  /** 构建被跳过的 parent 映射字符串用于 DEBUG 日志（evidence 中存在但未进入最终 sources 的 parent）. */
  static String buildSkippedParents(
      List<ParentEvidenceResult> evidence, List<QuerySource> sources) {
    if (evidence == null || evidence.isEmpty()) {
      return "";
    }
    java.util.Set<Long> includedParents =
        sources.stream()
            .map(QuerySource::parentChunkId)
            .collect(java.util.stream.Collectors.toSet());
    return evidence.stream()
        .filter(e -> !includedParents.contains(e.parentChunkId()))
        .map(e -> e.parentChunkId() + "→" + e.content().length())
        .collect(Collectors.joining(", "));
  }

  /** 解析提供商的名称字符串. */
  private String resolveProviderName() {
    try {
      return queryProperties.getLlm().provider().name().toLowerCase();
    } catch (Exception e) {
      return "unknown";
    }
  }

  /** 解析协议名称. */
  private String resolveProtocol() {
    try {
      QueryProperties.Provider provider = queryProperties.getLlm().provider();
      if (provider == QueryProperties.Provider.DEEPSEEK) {
        return "anthropic";
      }
      return "stub";
    } catch (Exception e) {
      return "unknown";
    }
  }

  /** 解析模型名称. */
  private String resolveModel() {
    try {
      QueryProperties.Provider provider = queryProperties.getLlm().provider();
      if (provider == QueryProperties.Provider.DEEPSEEK) {
        QueryProperties.DeepSeek ds = queryProperties.getLlm().deepseek();
        if (ds != null && ds.model() != null) {
          return ds.model();
        }
      }
      return provider.name().toLowerCase();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
