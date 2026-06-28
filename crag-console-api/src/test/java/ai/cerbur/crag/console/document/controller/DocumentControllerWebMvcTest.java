package ai.cerbur.crag.console.document.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.cerbur.crag.console.advice.GlobalExceptionHandler;
import ai.cerbur.crag.console.document.dto.DocumentListResponse;
import ai.cerbur.crag.console.document.dto.DocumentResponse;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.NotFoundException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient.RetryNotAllowedException;
import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseResponse;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import ai.cerbur.crag.contracts.knowledge.v1.Document;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * DocumentController HTTP 契约 MockMvc 测试（plan_21/21.8）。
 *
 * <p>standaloneSetup 装配真实 Controller + GlobalExceptionHandler；KnowledgeBaseOrchestrator 与
 * KnowledgeDocumentClient 使用 Mockito 替身。锁定 list/upload/get/retry 路由、状态码、上传校验错误码与负向映射。
 */
class DocumentControllerWebMvcTest {

  private MockMvc mvc;
  private KnowledgeBaseOrchestrator kbOrchestrator;
  private KnowledgeDocumentClient documentClient;

  @BeforeEach
  void setUp() {
    kbOrchestrator = mock(KnowledgeBaseOrchestrator.class);
    documentClient = mock(KnowledgeDocumentClient.class);
    DocumentController controller = new DocumentController(kbOrchestrator, documentClient);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("GET /documents 无 Principal → 401")
  void listUnauthenticatedReturns401() throws Exception {
    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases/100/documents")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("GET /documents → 200 items + nextPageToken")
  void listReturnsPaginated() throws Exception {
    when(kbOrchestrator.get(eq(123L), eq(1L), eq(100L))).thenReturn(kbResponse(true));
    when(documentClient.list(eq(1L), eq(100L), eq(20), eq("")))
        .thenReturn(
            new DocumentListResponse(
                List.of(
                    new DocumentResponse(
                        "200", "100", "a.txt", "TXT", 10, "PENDING", "1", 1, null, null, false,
                        null, null)),
                "200"));

    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases/100/documents")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.items[0].docId").value("200"))
        .andExpect(jsonPath("$.result.items[0].ingestionStatus").value("PENDING"))
        .andExpect(jsonPath("$.result.nextPageToken").value("200"));
  }

  @Test
  @DisplayName("GET /documents 跨租户 KB → 404（KB 归属先 Authorize 不泄漏）")
  void listCrossTenantKbReturns404() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong()))
        .thenThrow(new KnowledgeBaseOrchestrator.NotFoundException());

    mvc.perform(
            get("/api/v1/tenants/99/knowledge-bases/100/documents")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  @DisplayName("POST /documents upload 单 txt → 202 PENDING")
  void uploadTxtReturns202() throws Exception {
    when(kbOrchestrator.get(eq(123L), eq(1L), eq(100L))).thenReturn(kbResponse(true));
    when(documentClient.upload(any()))
        .thenReturn(
            Document.newBuilder()
                .setDocId("200")
                .setKnowledgeBaseId("100")
                .setOriginalFilename("note.txt")
                .setFileType("TXT")
                .setSizeBytes(9)
                .setIngestionStatus("PENDING")
                .setOperationVersion(1L)
                .setIngestionAttempt(1)
                .build());

    MockMultipartFile file =
        new MockMultipartFile("file", "note.txt", "text/plain", "hello txt".getBytes());

    mvc.perform(
            multipart("/api/v1/tenants/1/knowledge-bases/100/documents")
                .file(file)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.result.docId").value("200"))
        .andExpect(jsonPath("$.result.ingestionStatus").value("PENDING"))
        .andExpect(jsonPath("$.result.operationVersion").value("1"));
  }

  @Test
  @DisplayName("upload 单 md → 202")
  void uploadMdReturns202() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));
    when(documentClient.upload(any()))
        .thenReturn(
            Document.newBuilder()
                .setDocId("201")
                .setFileType("MARKDOWN")
                .setIngestionStatus("PENDING")
                .setOperationVersion(1L)
                .build());

    MockMultipartFile file =
        new MockMultipartFile("file", "readme.md", "text/markdown", "# title".getBytes());

    mvc.perform(
            multipart("/api/v1/tenants/1/knowledge-bases/100/documents")
                .file(file)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isAccepted());
  }

  @Test
  @DisplayName("upload 非法扩展名 (.pdf) → 415 UNSUPPORTED_MEDIA_TYPE")
  void uploadUnsupportedExtensionReturns415() throws Exception {
    // KB 归属通过后才到达 UploadValidation；这里 KB 归属放行
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));

    MockMultipartFile file =
        new MockMultipartFile("file", "doc.pdf", "application/pdf", "x".getBytes());

    mvc.perform(
            multipart("/api/v1/tenants/1/knowledge-bases/100/documents")
                .file(file)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value(41501));
  }

  @Test
  @DisplayName("upload 空 file → 400 VALIDATION_ERROR（EMPTY_FILE）")
  void uploadEmptyFileReturns400() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));

    MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

    mvc.perform(
            multipart("/api/v1/tenants/1/knowledge-bases/100/documents")
                .file(file)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("upload 非 UTF-8 → 400 VALIDATION_ERROR（NOT_UTF8）")
  void uploadNonUtf8Returns400() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "bad.txt", "text/plain", new byte[] {(byte) 0xFF, (byte) 0xFE});

    mvc.perform(
            multipart("/api/v1/tenants/1/knowledge-bases/100/documents")
                .file(file)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("upload KB 跨租户 → 404（不泄漏）")
  void uploadCrossTenantKbReturns404() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong()))
        .thenThrow(new KnowledgeBaseOrchestrator.NotFoundException());

    MockMultipartFile file =
        new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

    mvc.perform(
            multipart("/api/v1/tenants/99/knowledge-bases/100/documents")
                .file(file)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
    // UploadValidation 未触发（KB 归属先失败）
    verifyNoInteractions(documentClient);
  }

  @Test
  @DisplayName("GET /documents/{id} → 200 详情")
  void getReturnsDetail() throws Exception {
    when(kbOrchestrator.get(eq(123L), eq(1L), eq(100L))).thenReturn(kbResponse(true));
    when(documentClient.get(eq(1L), eq(100L), eq(200L)))
        .thenReturn(
            new DocumentResponse(
                "200",
                "100",
                "a.txt",
                "TXT",
                10,
                "FAILED",
                "1",
                1,
                "DISPATCH_MISSING",
                "transient",
                true,
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T00:01:00Z")));

    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases/100/documents/200")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.docId").value("200"))
        .andExpect(jsonPath("$.result.ingestionStatus").value("FAILED"))
        .andExpect(jsonPath("$.result.failureCategory").value("DISPATCH_MISSING"))
        .andExpect(jsonPath("$.result.retryable").value(true))
        .andExpect(jsonPath("$.result.attempt").value(1));
  }

  @Test
  @DisplayName("GET /documents/{id} 文档不存在 → 404")
  void getNotFoundReturns404() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));
    when(documentClient.get(anyLong(), anyLong(), anyLong())).thenThrow(new NotFoundException());

    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases/100/documents/999")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  @DisplayName("POST /documents/{id}/ingestion/retry → 200 新版本")
  void retryReturnsNewVersion() throws Exception {
    when(kbOrchestrator.get(eq(123L), eq(1L), eq(100L))).thenReturn(kbResponse(true));
    when(documentClient.retry(eq(123L), eq(1L), eq(100L), eq(200L)))
        .thenReturn(
            new DocumentResponse(
                "200", "100", "a.txt", "TXT", 10, "PENDING", "2", 2, null, null, false, null,
                null));

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases/100/documents/200/ingestion/retry")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.operationVersion").value("2"))
        .andExpect(jsonPath("$.result.attempt").value(2));

    verify(documentClient).retry(123L, 1L, 100L, 200L);
  }

  @Test
  @DisplayName("retry 不可重试 → 409 INGESTION_RETRY_NOT_ALLOWED")
  void retryNotAllowedReturns409() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));
    when(documentClient.retry(anyLong(), anyLong(), anyLong(), anyLong()))
        .thenThrow(new RetryNotAllowedException());

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases/100/documents/200/ingestion/retry")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40902));
  }

  @Test
  @DisplayName("retry 下游不可用 → 503")
  void retryDownstreamReturns503() throws Exception {
    when(kbOrchestrator.get(anyLong(), anyLong(), anyLong())).thenReturn(kbResponse(true));
    when(documentClient.retry(anyLong(), anyLong(), anyLong(), anyLong()))
        .thenThrow(new DownstreamUnavailableException());

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases/100/documents/200/ingestion/retry")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(50301));
  }

  private static KnowledgeBaseResponse kbResponse(boolean apiKeyReady) {
    return new KnowledgeBaseResponse(
        "100",
        "1",
        "kb-1",
        apiKeyReady,
        Instant.parse("2026-06-29T00:00:00Z"),
        Instant.parse("2026-06-29T00:00:00Z"));
  }
}
