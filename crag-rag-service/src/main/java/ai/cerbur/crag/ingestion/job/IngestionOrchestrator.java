package ai.cerbur.crag.ingestion.job;

import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitGroup;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitResult;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitService;
import ai.cerbur.crag.ingestion.consumer.DocUploadedPayload;
import ai.cerbur.crag.ingestion.knowledge.KnowledgeDocumentFileClient;
import ai.cerbur.crag.ingestion.knowledge.KnowledgeFileRead;
import ai.cerbur.crag.ingestion.knowledge.KnowledgeFileReadException;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.IngestionJobConflictException;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.IngestionJob;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ingestion 编排服务（Plan 19）—— 在消费 DOC_UPLOADED 后驱动单个 Job 的完整处理.
 *
 * <p>流程：CAS 推进 PENDING → PROCESSING；通过 Knowledge gRPC 读取文件并校验 sha256 / size / fileType；按 UTF-8
 * 解码并复用 {@link ChunkSplitService} 切分；批量写入携带 {@code knowledgeBaseId} 的 parent / child Chunk；尝试推进
 * READY。任一业务失败 推进 Job 为 FAILED 并记录安全分类与短摘要.
 *
 * <p>处理不在数据库事务中执行 gRPC 读取；CAS 冲突（Job 已被推进）视为并发幂等结果。状态事件发布由 19.6 在状态推进钩子接入.
 */
@Service
public class IngestionOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

  private static final String CHUNK_METADATA_JSON = "{}";

  @Autowired private IngestionJobService ingestionJobService;
  @Autowired private KnowledgeDocumentFileClient knowledgeFileClient;
  @Autowired private ChunkSplitService chunkSplitService;
  @Autowired private ChunkDao chunkDao;
  @Autowired private CragIdGenerator cragIdGenerator;

  /**
   * 处理单个 Job：读取、校验、切分、写入，并推进状态.
   *
   * @param job 待处理 Job（PROCESSING 前的 PENDING 视图）
   * @param payload DOC_UPLOADED 解析后的安全 payload
   */
  public void process(IngestionJob job, DocUploadedPayload payload) {
    try {
      ingestionJobService.markProcessing(job);
    } catch (IngestionJobConflictException e) {
      log.info(
          "Ingestion job already advanced, skip processing — docId={} operationVersion={}",
          job.getDocId(),
          job.getOperationVersion());
      return;
    }

    try {
      KnowledgeFileRead file =
          knowledgeFileClient.read(payload.tenantId(), payload.knowledgeBaseId(), payload.docId());
      verifySha256(file, payload.sha256());
      verifySize(file, payload.sizeBytes());
      verifyFileType(file, payload.fileType());
      String text = decodeUtf8(file.content());
      ChunkSplitResult split;
      try {
        split = chunkSplitService.split(text);
      } catch (RuntimeException e) {
        throw new IngestionBusinessFailure(
            IngestionJobFailureCategory.CHUNK_SPLIT_FAILED, "chunk split failed");
      }
      writeChunks(payload, split);
      ingestionJobService.tryAdvanceReadyIfComplete(payload.docId());
      log.info(
          "Ingestion processed — docId={} knowledgeBaseId={} parentGroups={}",
          payload.docId(),
          payload.knowledgeBaseId(),
          split.chunkGroups().size());
    } catch (IngestionBusinessFailure bf) {
      markFailedSafe(job, bf.getCategory(), bf.getMessage());
    } catch (KnowledgeFileReadException e) {
      markFailedSafe(job, IngestionJobFailureCategory.FILE_READ_FAILED, e.getMessage());
    } catch (RuntimeException e) {
      markFailedSafe(job, IngestionJobFailureCategory.UNKNOWN, e.getMessage());
    }
  }

  private void verifySha256(KnowledgeFileRead file, String expectedSha256) {
    if (!Objects.equals(file.sha256(), expectedSha256)) {
      throw new IngestionBusinessFailure(
          IngestionJobFailureCategory.FILE_CHECKSUM_MISMATCH, "sha256 mismatch");
    }
  }

  private void verifySize(KnowledgeFileRead file, long expectedSize) {
    if (file.sizeBytes() != expectedSize) {
      throw new IngestionBusinessFailure(
          IngestionJobFailureCategory.FILE_SIZE_MISMATCH, "size mismatch");
    }
  }

  private void verifyFileType(KnowledgeFileRead file, String expectedFileType) {
    if (!DocUploadedPayload.SUPPORTED_FILE_TYPES.contains(file.fileType())) {
      throw new IngestionBusinessFailure(
          IngestionJobFailureCategory.FILE_TYPE_UNSUPPORTED,
          "unsupported fileType: " + file.fileType());
    }
    if (!Objects.equals(file.fileType(), expectedFileType)) {
      throw new IngestionBusinessFailure(
          IngestionJobFailureCategory.FILE_TYPE_UNSUPPORTED, "fileType mismatch");
    }
  }

  private String decodeUtf8(byte[] content) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IngestionBusinessFailure(
          IngestionJobFailureCategory.FILE_DECODE_FAILED, "file is not valid UTF-8");
    }
  }

  private void writeChunks(DocUploadedPayload payload, ChunkSplitResult split) {
    List<Chunk> allChunks = new ArrayList<>();
    for (ChunkSplitGroup group : split.chunkGroups()) {
      long parentChunkId = cragIdGenerator.nextId(IdEntityType.CHUNK);
      Chunk parent =
          Chunk.createParent(
              parentChunkId,
              payload.knowledgeBaseId(),
              payload.docId(),
              payload.operationVersion(),
              group.parentChunk().content(),
              group.parentChunk().tokenCount(),
              group.parentChunk().chunkIndex(),
              CHUNK_METADATA_JSON);
      allChunks.add(parent);
      for (var childData : group.childChunks()) {
        long childChunkId = cragIdGenerator.nextId(IdEntityType.CHUNK);
        Chunk child =
            Chunk.createChild(
                childChunkId,
                payload.knowledgeBaseId(),
                payload.docId(),
                payload.operationVersion(),
                parentChunkId,
                childData.content(),
                childData.tokenCount(),
                childData.chunkIndex(),
                CHUNK_METADATA_JSON);
        allChunks.add(child);
      }
    }
    if (!allChunks.isEmpty()) {
      chunkDao.saveAll(allChunks);
    }
  }

  private void markFailedSafe(
      IngestionJob job, IngestionJobFailureCategory category, String message) {
    try {
      ingestionJobService.markFailed(job, category, message);
    } catch (IngestionJobConflictException e) {
      log.warn(
          "markFailed conflict, job already advanced — docId={} operationVersion={}",
          job.getDocId(),
          job.getOperationVersion());
    }
  }
}
