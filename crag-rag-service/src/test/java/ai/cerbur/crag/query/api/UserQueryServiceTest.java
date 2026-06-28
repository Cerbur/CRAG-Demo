package ai.cerbur.crag.query.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import ai.cerbur.crag.query.context.ContextBuilder;
import ai.cerbur.crag.query.context.QueryContext;
import ai.cerbur.crag.query.context.SourceBoundaryFactory;
import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.contract.LlmClient;
import ai.cerbur.crag.query.llm.contract.LlmFailureCategory;
import ai.cerbur.crag.query.llm.contract.LlmProviderException;
import ai.cerbur.crag.query.llm.contract.LlmRequest;
import ai.cerbur.crag.query.llm.contract.LlmResult;
import ai.cerbur.crag.query.llm.contract.LlmUsage;
import ai.cerbur.crag.query.prompt.PromptBuilder;
import ai.cerbur.crag.query.reference.ReferenceAnalysis;
import ai.cerbur.crag.query.reference.ReferenceAnalyzer;
import ai.cerbur.crag.retrieval.api.RetrievalService;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/** UserQueryService 单元测试 —— 使用 Mockito Mock 所有边界依赖. */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserQueryService 编排")
class UserQueryServiceTest {

  @Mock private RetrievalService retrievalService;
  @Mock private ContextBuilder contextBuilder;
  @Mock private PromptBuilder promptBuilder;
  @Mock private LlmClient llmClient;
  @Mock private ReferenceAnalyzer referenceAnalyzer;
  @Mock private QueryProperties queryProperties;

  @InjectMocks private UserQueryService service;

  private static final long KB = 31337L;
  private static final String REQUEST_ID = "test-request-id-123";
  private static final String QUESTION = "什么是CRAG？";
  private static final String TRIMMED_QUESTION = "什么是CRAG？";
  private static final String LLM_ANSWER = "CRAG是一种检索增强生成技术[S1]。";
  private static final String PARENT_CONTENT = "CRAG是一种检索增强生成（Retrieval-Augmented Generation）技术。";
  private static final long PARENT_CHUNK_ID = 100L;
  private static final List<Long> MATCHED_CHILD_IDS = List.of(1001L, 1002L);
  private static final String CONTEXT_TEXT =
      "<CRAG:abc:S1>\n" + PARENT_CONTENT + "\n</CRAG:abc:S1>";
  private static final int CONTEXT_LENGTH = CONTEXT_TEXT.length();

  private QueryProperties.Retrieval retrievalConfig;
  private QueryProperties.Context contextConfig;
  private QueryProperties.Llm llmConfig;

  @BeforeEach
  void setUp() {
    retrievalConfig = new QueryProperties.Retrieval(8);
    contextConfig = new QueryProperties.Context(12000);
    llmConfig =
        new QueryProperties.Llm(
            QueryProperties.Provider.STUB,
            java.time.Duration.ofSeconds(120),
            null,
            new QueryProperties.Stub(QueryProperties.StubMode.SUCCESS));

    lenient().when(queryProperties.getRetrieval()).thenReturn(retrievalConfig);
    lenient().when(queryProperties.getContext()).thenReturn(contextConfig);
    lenient().when(queryProperties.getLlm()).thenReturn(llmConfig);
  }

  private ParentEvidenceResult makeEvidence() {
    return new ParentEvidenceResult(PARENT_CHUNK_ID, 7000L, PARENT_CONTENT, MATCHED_CHILD_IDS);
  }

  private QuerySource makeSource() {
    return new QuerySource("S1", PARENT_CHUNK_ID, MATCHED_CHILD_IDS);
  }

  private QueryContext makeContext() {
    return new QueryContext(CONTEXT_TEXT, List.of(makeSource()), CONTEXT_LENGTH);
  }

  // ============================================================
  // 正常流程
  // ============================================================

  @Nested
  @DisplayName("正常流程")
  class NormalFlow {

    @Test
    @DisplayName("完整流程：问题 → 证据 → 上下文 → LLM → 分析 → 结果")
    void fullPipeline() {
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      QueryContext queryContext = makeContext();
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user prompt", 1);
      given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

      LlmResult llmResult = new LlmResult(LLM_ANSWER, null);
      given(llmClient.generate(llmRequest)).willReturn(llmResult);

      given(referenceAnalyzer.analyze(LLM_ANSWER, 1))
          .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);

      assertThat(result.answer()).isEqualTo(LLM_ANSWER);
      assertThat(result.sources()).hasSize(1);
      assertThat(result.sources().get(0).reference()).isEqualTo("S1");
      assertThat(result.sources().get(0).parentChunkId()).isEqualTo(PARENT_CHUNK_ID);

      then(retrievalService).should().retrieveEvidence(KB, TRIMMED_QUESTION, 8);
      then(contextBuilder).should().build(anyList(), anyInt(), any(SourceBoundaryFactory.class));
      then(promptBuilder).should().build(TRIMMED_QUESTION, queryContext);
      then(llmClient).should().generate(llmRequest);
      then(referenceAnalyzer).should().analyze(LLM_ANSWER, 1);
    }
  }

  // ============================================================
  // 无效查询
  // ============================================================

  @Nested
  @DisplayName("输入校验")
  class InputValidation {

    @Test
    @DisplayName("null 问题 → InvalidQueryException(QUESTION_REQUIRED)")
    void nullQuestion() {
      assertThatThrownBy(() -> service.answer(KB, null))
          .isInstanceOf(InvalidQueryException.class)
          .hasFieldOrPropertyWithValue("reason", InvalidQueryException.Reason.QUESTION_REQUIRED);
    }

    @Test
    @DisplayName("空白问题 → InvalidQueryException(QUESTION_REQUIRED)")
    void blankQuestion() {
      assertThatThrownBy(() -> service.answer(KB, "   "))
          .isInstanceOf(InvalidQueryException.class)
          .hasFieldOrPropertyWithValue("reason", InvalidQueryException.Reason.QUESTION_REQUIRED);
    }

    @Test
    @DisplayName("空字符串 → InvalidQueryException(QUESTION_REQUIRED)")
    void emptyQuestion() {
      assertThatThrownBy(() -> service.answer(KB, ""))
          .isInstanceOf(InvalidQueryException.class)
          .hasFieldOrPropertyWithValue("reason", InvalidQueryException.Reason.QUESTION_REQUIRED);
    }

    @Test
    @DisplayName("超长问题 (>2000) → InvalidQueryException(QUESTION_TOO_LONG)")
    void questionTooLong() {
      String longQuestion = "A".repeat(2001);

      assertThatThrownBy(() -> service.answer(KB, longQuestion))
          .isInstanceOf(InvalidQueryException.class)
          .hasFieldOrPropertyWithValue("reason", InvalidQueryException.Reason.QUESTION_TOO_LONG);
    }

    @Test
    @DisplayName("恰好 2000 字符 → 正常通过")
    void exactlyMaxLength() {
      String exactQuestion = "B".repeat(2000);
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, exactQuestion, 8)).willReturn(List.of(evidence));

      QueryContext queryContext = makeContext();
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user", 1);
      given(promptBuilder.build(exactQuestion, queryContext)).willReturn(llmRequest);

      LlmResult llmResult = new LlmResult("answer with [S1]", null);
      given(llmClient.generate(llmRequest)).willReturn(llmResult);

      given(referenceAnalyzer.analyze("answer with [S1]", 1))
          .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

      UserQueryResult result = service.answer(KB, exactQuestion);
      assertThat(result.answer()).isEqualTo("answer with [S1]");
    }

    @Test
    @DisplayName("问题应被 trim")
    void questionIsTrimmed() {
      String questionWithSpaces = "  什么是CRAG？  ";
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      QueryContext queryContext = makeContext();
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user", 1);
      given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

      LlmResult llmResult = new LlmResult("answer [S1]", null);
      given(llmClient.generate(llmRequest)).willReturn(llmResult);

      given(referenceAnalyzer.analyze("answer [S1]", 1))
          .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

      UserQueryResult result = service.answer(KB, questionWithSpaces);
      assertThat(result.answer()).isEqualTo("answer [S1]");
    }
  }

  // ============================================================
  // 空证据 / 空上下文
  // ============================================================

  @Nested
  @DisplayName("空上下文处理")
  class EmptyContext {

    @Test
    @DisplayName("检索返回空列表 → 知识库证据不足，不调用 LLM")
    void emptyRetrieval() {
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8)).willReturn(List.of());

      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(new QueryContext("", List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);

      assertThat(result.answer()).isEqualTo(UserQueryService.INSUFFICIENT_EVIDENCE_ANSWER);
      assertThat(result.sources()).isEmpty();

      then(llmClient).shouldHaveNoInteractions();
      then(promptBuilder).shouldHaveNoInteractions();
      then(referenceAnalyzer).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("检索返回 null → 知识库证据不足，不调用 LLM")
    void nullRetrieval() {
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8)).willReturn(null);

      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(new QueryContext("", List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);

      assertThat(result.answer()).isEqualTo(UserQueryService.INSUFFICIENT_EVIDENCE_ANSWER);
      assertThat(result.sources()).isEmpty();
      then(llmClient).shouldHaveNoInteractions();
      then(referenceAnalyzer).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("有证据但被预算全部跳过 → 知识库证据不足，不调用 LLM")
    void budgetEmptyContext() {
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(new QueryContext("", List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);

      assertThat(result.answer()).isEqualTo(UserQueryService.INSUFFICIENT_EVIDENCE_ANSWER);
      assertThat(result.sources()).isEmpty();
      then(llmClient).shouldHaveNoInteractions();
      then(referenceAnalyzer).shouldHaveNoInteractions();
    }
  }

  // ============================================================
  // 异常处理
  // ============================================================

  @Nested
  @DisplayName("异常处理")
  class ExceptionHandling {

    @Test
    @DisplayName("RetrievalService 抛出异常 → 包装为 RuntimeException")
    void retrievalServiceThrows() {
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willThrow(new RuntimeException("DB connection failed"));

      assertThatThrownBy(() -> service.answer(KB, QUESTION))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("Retrieval service failed")
          .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("LLM ProviderException → LlmUnavailableException")
    void llmProviderException() {
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      QueryContext queryContext = makeContext();
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user", 1);
      given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

      LlmProviderException providerEx =
          new LlmProviderException(LlmFailureCategory.SERVER_ERROR, "Service overloaded", null);
      given(llmClient.generate(llmRequest)).willThrow(providerEx);

      assertThatThrownBy(() -> service.answer(KB, QUESTION))
          .isInstanceOf(LlmUnavailableException.class)
          .hasMessage("LLM provider failure: SERVER_ERROR")
          .hasCause(providerEx);
    }
  }

  // ============================================================
  // MDC requestId
  // ============================================================

  @Nested
  @DisplayName("MDC requestId")
  class MdcRequestId {

    @Test
    @DisplayName("MDC 中无 requestId → 自动生成 UUID 格式")
    void generatesRequestId() {
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      QueryContext queryContext = makeContext();
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user", 1);
      given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

      LlmResult llmResult = new LlmResult("answer [S1]", null);
      given(llmClient.generate(llmRequest)).willReturn(llmResult);

      given(referenceAnalyzer.analyze("answer [S1]", 1))
          .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

      service.answer(KB, QUESTION);

      String currentRequestId = MDC.get("requestId");
      assertThat(currentRequestId).isNull();
    }

    @Test
    @DisplayName("MDC 中已有 requestId → 复用")
    void reusesExistingRequestId() {
      try {
        MDC.put("requestId", REQUEST_ID);

        ParentEvidenceResult evidence = makeEvidence();
        given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
            .willReturn(List.of(evidence));

        QueryContext queryContext = makeContext();
        given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
            .willReturn(queryContext);

        LlmRequest llmRequest = new LlmRequest("system", "user", 1);
        given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

        LlmResult llmResult = new LlmResult("answer [S1]", null);
        given(llmClient.generate(llmRequest)).willReturn(llmResult);

        given(referenceAnalyzer.analyze("answer [S1]", 1))
            .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

        service.answer(KB, QUESTION);

        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
      } finally {
        MDC.remove("requestId");
      }
    }

    @Test
    @DisplayName("异常后 MDC requestId 被正确恢复")
    void mdcRestoredAfterException() {
      try {
        MDC.put("requestId", REQUEST_ID);

        given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
            .willThrow(new RuntimeException("fail"));

        assertThatThrownBy(() -> service.answer(KB, QUESTION)).isInstanceOf(RuntimeException.class);

        assertThat(MDC.get("requestId")).isEqualTo(REQUEST_ID);
      } finally {
        MDC.remove("requestId");
      }
    }

    @Test
    @DisplayName("最初无 MDC 时异常后 MDC 被清除")
    void mdcClearedAfterExceptionWhenNoOriginal() {
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willThrow(new RuntimeException("fail"));

      assertThatThrownBy(() -> service.answer(KB, QUESTION)).isInstanceOf(RuntimeException.class);

      assertThat(MDC.get("requestId")).isNull();
    }
  }

  // ============================================================
  // 结果正确性
  // ============================================================

  @Nested
  @DisplayName("结果正确性")
  class ResultCorrectness {

    @Test
    @DisplayName("sources 从 QueryContext 传入 UserQueryResult")
    void sourcesPassedToResult() {
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      QuerySource querySource = makeSource();
      QueryContext queryContext =
          new QueryContext(CONTEXT_TEXT, List.of(querySource), CONTEXT_LENGTH);
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user", 1);
      given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

      LlmResult llmResult = new LlmResult("answer [S1]", null);
      given(llmClient.generate(llmRequest)).willReturn(llmResult);

      given(referenceAnalyzer.analyze("answer [S1]", 1))
          .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);

      assertThat(result.sources()).containsExactly(querySource);
    }

    @Test
    @DisplayName("LLM 使用 null/available 时正常工作")
    void llmUsageOptional() {
      ParentEvidenceResult evidence = makeEvidence();
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8))
          .willReturn(List.of(evidence));

      QueryContext queryContext = makeContext();
      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(queryContext);

      LlmRequest llmRequest = new LlmRequest("system", "user", 1);
      given(promptBuilder.build(TRIMMED_QUESTION, queryContext)).willReturn(llmRequest);

      LlmResult llmResult = new LlmResult("answer with usage [S1]", new LlmUsage(150, 75, 10));
      given(llmClient.generate(llmRequest)).willReturn(llmResult);

      given(referenceAnalyzer.analyze("answer with usage [S1]", 1))
          .willReturn(new ReferenceAnalysis(1, 1, 1, List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);
      assertThat(result.answer()).isEqualTo("answer with usage [S1]");
    }
  }

  // ============================================================
  // 调用顺序验证
  // ============================================================

  @Nested
  @DisplayName("调用顺序")
  class InvocationOrder {

    @Test
    @DisplayName("LLM 未在空上下文时调用")
    void llmNotCalledForEmptyContext() {
      given(retrievalService.retrieveEvidence(KB, TRIMMED_QUESTION, 8)).willReturn(List.of());

      given(contextBuilder.build(anyList(), anyInt(), any(SourceBoundaryFactory.class)))
          .willReturn(new QueryContext("", List.of(), 0));

      UserQueryResult result = service.answer(KB, QUESTION);

      assertThat(result.answer()).isEqualTo(UserQueryService.INSUFFICIENT_EVIDENCE_ANSWER);
      then(llmClient).shouldHaveNoInteractions();
      then(promptBuilder).shouldHaveNoInteractions();
      then(referenceAnalyzer).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("输入校验失败时所有下游均未调用")
    void noDownstreamOnValidationFailure() {
      assertThatThrownBy(() -> service.answer(KB, null)).isInstanceOf(InvalidQueryException.class);

      then(retrievalService).shouldHaveNoInteractions();
      then(contextBuilder).shouldHaveNoInteractions();
      then(promptBuilder).shouldHaveNoInteractions();
      then(llmClient).shouldHaveNoInteractions();
      then(referenceAnalyzer).shouldHaveNoInteractions();
    }
  }

  // ============================================================
  // sanitizeForLog
  // ============================================================

  @Nested
  @DisplayName("sanitizeForLog")
  class SanitizeForLog {

    @Test
    @DisplayName("null → null")
    void nullInput() {
      assertThat(UserQueryService.sanitizeForLog(null)).isNull();
    }

    @Test
    @DisplayName("普通字符串不变")
    void plainString() {
      assertThat(UserQueryService.sanitizeForLog("hello")).isEqualTo("hello");
    }

    @Test
    @DisplayName("\\r → \\\\r")
    void carriageReturn() {
      assertThat(UserQueryService.sanitizeForLog("line1\rline2")).isEqualTo("line1\\rline2");
    }

    @Test
    @DisplayName("\\n → \\\\n")
    void newline() {
      assertThat(UserQueryService.sanitizeForLog("line1\nline2")).isEqualTo("line1\\nline2");
    }

    @Test
    @DisplayName("同时替换 \\r 和 \\n")
    void both() {
      assertThat(UserQueryService.sanitizeForLog("a\r\nb")).isEqualTo("a\\r\\nb");
    }
  }
}
