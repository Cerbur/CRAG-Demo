package ai.cerbur.crag.smoke.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.smoke.ingestion.RagIngestionSmokeService;
import ai.cerbur.crag.smoke.ingestion.RagIngestionSmokeService.IngestionEventStatus;
import ai.cerbur.crag.storage.entity.IngestionJob;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * router2 RAG ingestion smoke 诊断端点（Plan 19），仅 smoke Profile 启用.
 *
 * <p>统一前缀 {@code /api/v1/smoke/rag/ingestion}，供 Docker HTTP 回归脚本：
 *
 * <ul>
 *   <li>轮询 Job 状态直到 READY / FAILED；
 *   <li>观察 INGESTION_PROCESSING / READY / FAILED 状态事件；
 *   <li>校验重复 DOC_UPLOADED 不重复建 chunk。
 * </ul>
 *
 * <p>默认 profile 不暴露本端点。查询入口使用既有 {@code /api/v1/smoke/query}（携带 knowledgeBaseId）.
 */
@RestController
@Profile("smoke")
@RequestMapping("/api/v1/smoke/rag/ingestion")
public class RagIngestionSmokeController {

  @Autowired private RagIngestionSmokeService service;

  /** 查询 Job 状态（按 KB + docId）. */
  @GetMapping("/job")
  public Response<JobStatusResponse> job(
      @RequestParam("knowledgeBaseId") long knowledgeBaseId, @RequestParam("docId") long docId) {
    Optional<IngestionJob> job = service.findJob(knowledgeBaseId, docId);
    return Response.success(
        job.map(
                j ->
                    new JobStatusResponse(
                        j.getKnowledgeBaseId(),
                        j.getDocId(),
                        j.getOperationVersion(),
                        j.getJobId() == null ? null : j.getJobId().toString(),
                        j.getStatus().name(),
                        j.getFailureCategory(),
                        j.getFailureMessage()))
            .orElse(null));
  }

  /** 查询某文档的 ingestion 状态事件. */
  @GetMapping("/events")
  public Response<List<IngestionEventStatus>> events(@RequestParam("docId") long docId) {
    return Response.success(service.findStatusEvents(docId));
  }

  /** 统计某文档的 chunk 行数（幂等校验）. */
  @GetMapping("/chunks/count")
  public Response<ChunkCountResponse> chunkCount(@RequestParam("docId") long docId) {
    return Response.success(new ChunkCountResponse(docId, service.countChunksByDocId(docId)));
  }

  /** Job 状态响应. */
  public record JobStatusResponse(
      long knowledgeBaseId,
      long docId,
      long operationVersion,
      String jobId,
      String status,
      String failureCategory,
      String failureMessage) {}

  /** chunk 计数响应. */
  public record ChunkCountResponse(long docId, long chunkCount) {}
}
