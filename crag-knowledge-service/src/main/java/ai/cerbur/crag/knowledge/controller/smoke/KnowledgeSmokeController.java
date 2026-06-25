package ai.cerbur.crag.knowledge.controller.smoke;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.knowledge.controller.smoke.dto.CreateKnowledgeBaseSmokeRequest;
import ai.cerbur.crag.knowledge.controller.smoke.dto.DocumentEventSmokeResponse;
import ai.cerbur.crag.knowledge.controller.smoke.dto.DocumentSmokeResponse;
import ai.cerbur.crag.knowledge.controller.smoke.dto.KnowledgeBaseSmokeResponse;
import ai.cerbur.crag.knowledge.core.document.DocumentQueryService;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.core.document.DocumentUploadCommand;
import ai.cerbur.crag.knowledge.core.document.DocumentUploadService;
import ai.cerbur.crag.knowledge.core.document.FileType;
import ai.cerbur.crag.knowledge.core.document.UploadHandle;
import ai.cerbur.crag.knowledge.core.file.FileRead;
import ai.cerbur.crag.knowledge.core.file.FileReadService;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseResult;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseService;
import ai.cerbur.crag.knowledge.producer.KnowledgeEventTypes;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * smoke-only Knowledge HTTP 验收入口（{@code smoke} Profile）。复用 core 用例服务，证明 KnowledgeBase 创建、Document
 * 流式上传、查询、 文件读取与 DOC_UPLOADED 发布诊断的真实链路。默认 profile 不装配本 Controller。
 */
@RestController
@Profile("smoke")
@RequestMapping("/api/v1/smoke/knowledge")
public class KnowledgeSmokeController {

  private static final int BUFFER_SIZE = 8192;

  @Autowired private KnowledgeBaseService knowledgeBaseService;
  @Autowired private DocumentUploadService uploadService;
  @Autowired private DocumentQueryService queryService;
  @Autowired private FileReadService fileReadService;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 创建知识库。 */
  @PostMapping("/knowledge-bases")
  public Response<KnowledgeBaseSmokeResponse> createKnowledgeBase(
      @RequestBody @Valid CreateKnowledgeBaseSmokeRequest request) {
    KnowledgeBaseResult result =
        knowledgeBaseService.create(
            Long.parseLong(request.tenantId()),
            request.name(),
            Long.parseLong(request.createdByUserId()));
    return Response.success(toResponse(result));
  }

  /** 查询知识库。 */
  @GetMapping("/knowledge-bases/{knowledgeBaseId}")
  public Response<KnowledgeBaseSmokeResponse> getKnowledgeBase(
      @PathVariable String knowledgeBaseId, @RequestParam String tenantId) {
    KnowledgeBaseResult result =
        knowledgeBaseService
            .get(Long.parseLong(knowledgeBaseId), Long.parseLong(tenantId))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge base not found"));
    return Response.success(toResponse(result));
  }

  /**
   * multipart 上传文档。客户端提供 sha256 与声明大小；文件类型由原始文件名扩展名推导。校验失败抛 IllegalArgumentException 由 {@link
   * KnowledgeSmokeExceptionHandler} 映射为 400，且不创建 Document。
   */
  @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Response<DocumentSmokeResponse> uploadDocument(
      @RequestParam String tenantId,
      @RequestParam String knowledgeBaseId,
      @RequestParam String uploadedByUserId,
      @RequestParam String sha256,
      @RequestParam long sizeBytes,
      @RequestParam("file") MultipartFile file) {
    FileType fileType = FileType.fromFilename(file.getOriginalFilename());
    DocumentUploadCommand command =
        new DocumentUploadCommand(
            Long.parseLong(tenantId),
            Long.parseLong(knowledgeBaseId),
            Long.parseLong(uploadedByUserId),
            file.getOriginalFilename(),
            fileType,
            sizeBytes,
            sha256);
    UploadHandle handle = uploadService.begin(command);
    try (InputStream input = file.getInputStream()) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        uploadService.append(handle, buffer, 0, read);
      }
    } catch (IOException e) {
      uploadService.abort(handle);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "failed to read upload", e);
    }
    return Response.success(toResponse(uploadService.complete(handle)));
  }

  /** 查询文档。 */
  @GetMapping("/documents/{docId}")
  public Response<DocumentSmokeResponse> getDocument(
      @PathVariable String docId, @RequestParam String tenantId) {
    DocumentResult result =
        queryService
            .get(Long.parseLong(docId), Long.parseLong(tenantId))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));
    return Response.success(toResponse(result));
  }

  /** 读回文档原始内容（text/plain）。 */
  @GetMapping(value = "/documents/{docId}/file", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<byte[]> readDocumentFile(
      @PathVariable String docId, @RequestParam String tenantId) {
    FileRead read =
        fileReadService
            .open(Long.parseLong(docId), Long.parseLong(tenantId))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));
    try (InputStream input = read.content()) {
      return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(input.readAllBytes());
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read file", e);
    }
  }

  /** 查询文档的 DOC_UPLOADED 发布诊断（outbox 状态与尝试次数）。 */
  @GetMapping("/documents/{docId}/event")
  public Response<DocumentEventSmokeResponse> documentEventStatus(@PathVariable String docId) {
    List<DocumentEventSmokeResponse> rows =
        jdbcTemplate.query(
            "SELECT status, attempt_count FROM outbox_event"
                + " WHERE resource_id = ? AND event_type = '"
                + KnowledgeEventTypes.DOC_UPLOADED
                + "' ORDER BY event_id DESC LIMIT 1",
            (rs, i) ->
                new DocumentEventSmokeResponse(
                    docId, rs.getString("status"), rs.getInt("attempt_count")),
            Long.parseLong(docId));
    Optional<DocumentEventSmokeResponse> status = rows.stream().findFirst();
    return Response.success(
        status.orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "event not found")));
  }

  private static KnowledgeBaseSmokeResponse toResponse(KnowledgeBaseResult result) {
    return new KnowledgeBaseSmokeResponse(
        Long.toString(result.knowledgeBaseId()),
        Long.toString(result.tenantId()),
        result.name(),
        result.status());
  }

  private static DocumentSmokeResponse toResponse(DocumentResult result) {
    return new DocumentSmokeResponse(
        Long.toString(result.docId()),
        Long.toString(result.knowledgeBaseId()),
        Long.toString(result.tenantId()),
        result.fileType().name(),
        result.sizeBytes(),
        result.sha256(),
        result.ingestionStatus(),
        result.operationVersion());
  }
}
