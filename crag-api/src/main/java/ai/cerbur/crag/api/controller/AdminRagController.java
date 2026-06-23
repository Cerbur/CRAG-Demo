package ai.cerbur.crag.api.controller;

import ai.cerbur.crag.api.dto.rag.AdminRagRequest;
import ai.cerbur.crag.api.dto.rag.AdminRagResponse;
import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.ingestion.api.AdminRagResult;
import ai.cerbur.crag.ingestion.api.AdminRagService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 RAG 知识库上传接口 —— 接收纯文本内容，分块入库并异步完成向量化.
 *
 * <p>POST /api/v1/admin/rag 接收 AdminRagRequest JSON，委托 AdminRagService 执行分块与持久化， 返回统一 Response 包装的
 * AdminRagResponse.
 *
 * @since 2026-06-10
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminRagController {

  /** 管理端 RAG 服务，编排分块 + 写入. */
  @Autowired private AdminRagService adminRagService;

  /**
   * 知识库上传 —— 接收文档文本，分块写入 chunk 表，返回 docId 及分块数量.
   *
   * @param request 文档 title、content 及可选 metadata（@Valid 自动校验 title/content 非空）
   * @return Response 包装的 AdminRagResponse（docId、chunks、status、parentChunkIds）
   */
  @PostMapping("/rag")
  public Response<AdminRagResponse> upload(@Valid @RequestBody AdminRagRequest request) {
    AdminRagResult result =
        adminRagService.ingest(request.title(), request.content(), request.metadata());
    AdminRagResponse response =
        new AdminRagResponse(
            Long.toString(result.docId()),
            result.chunks(),
            result.status(),
            result.parentChunkIds().stream().map(id -> Long.toString(id)).toList());
    return Response.success(response);
  }
}
