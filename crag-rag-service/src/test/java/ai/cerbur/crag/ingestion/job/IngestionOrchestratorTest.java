package ai.cerbur.crag.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitData;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IngestionOrchestrator 单元测试（Plan 19）：验证读取、校验、解码、切分、写入与失败分类映射.
 *
 * <p>Knowledge 客户端、ChunkSplit、DAO 与状态服务通过 Mockito 隔离；真实 gRPC 读取与 PostgreSQL 写入由 Docker
 * 回归证明（plan_19.7）.
 */
@DisplayName("IngestionOrchestrator 编排与失败分类")
@ExtendWith(MockitoExtension.class)
class IngestionOrchestratorTest {

  private static final long KB = 200L;
  private static final long DOC = 1001L;
  private static final String SHA = "sha-abc";
  private static final long SIZE = 11L;

  @Mock private IngestionJobService ingestionJobService;
  @Mock private KnowledgeDocumentFileClient knowledgeFileClient;
  @Mock private ChunkSplitService chunkSplitService;
  @Mock private ChunkDao chunkDao;
  @Mock private CragIdGenerator cragIdGenerator;

  @InjectMocks private IngestionOrchestrator orchestrator;

  private final AtomicLong chunkIds = new AtomicLong(5000L);

  @BeforeEach
  void setUp() {
    lenient()
        .when(cragIdGenerator.nextId(IdEntityType.CHUNK))
        .thenAnswer(i -> chunkIds.getAndIncrement());
  }

  private DocUploadedPayload payload(String fileType) {
    return new DocUploadedPayload(7L, KB, DOC, 1L, fileType, SIZE, SHA);
  }

  private IngestionJob job() {
    return IngestionJob.createPending(7L, KB, DOC, 1L, "TXT", SIZE, SHA);
  }

  private KnowledgeFileRead file(String sha, long size, String fileType, String text) {
    return new KnowledgeFileRead(size, sha, fileType, text.getBytes(StandardCharsets.UTF_8));
  }

  private void stubSplitOneGroup(String content) {
    ChunkSplitGroup group =
        new ChunkSplitGroup(
            new ChunkSplitData(content, 10, 0), List.of(new ChunkSplitData(content, 5, 0)));
    when(chunkSplitService.split(content)).thenReturn(new ChunkSplitResult(List.of(group)));
  }

  @Test
  @DisplayName("成功 → markProcessing、写入带 KB 的 chunk、尝试推进 READY；不 markFailed")
  void successWritesKbScopedChunksAndAdvancesReady() {
    String content = "hello world";
    when(knowledgeFileClient.read(7L, KB, DOC)).thenReturn(file(SHA, SIZE, "TXT", content));
    stubSplitOneGroup(content);

    orchestrator.process(job(), payload("TXT"));

    verify(ingestionJobService).markProcessing(any());
    verify(ingestionJobService).tryAdvanceReadyIfComplete(DOC);
    verify(ingestionJobService, never()).markFailed(any(), any(), any());

    ArgumentCaptor<List<Chunk>> captor = captureSavedChunks();
    assertThat(captor.getValue()).isNotEmpty();
    assertThat(captor.getValue()).allMatch(c -> c.getKnowledgeBaseId() == KB);
    assertThat(captor.getValue()).allMatch(c -> c.getDocId() == DOC);
  }

  @Test
  @DisplayName("sha256 不一致 → markFailed(FILE_CHECKSUM_MISMATCH)")
  void checksumMismatchFails() {
    when(knowledgeFileClient.read(7L, KB, DOC)).thenReturn(file("wrong-sha", SIZE, "TXT", "x"));

    orchestrator.process(job(), payload("TXT"));

    verifyFailed(IngestionJobFailureCategory.FILE_CHECKSUM_MISMATCH);
    verify(chunkDao, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("size 不一致 → markFailed(FILE_SIZE_MISMATCH)")
  void sizeMismatchFails() {
    when(knowledgeFileClient.read(7L, KB, DOC)).thenReturn(file(SHA, 999L, "TXT", "x"));

    orchestrator.process(job(), payload("TXT"));

    verifyFailed(IngestionJobFailureCategory.FILE_SIZE_MISMATCH);
  }

  @Test
  @DisplayName("fileType 不一致 → markFailed(FILE_TYPE_UNSUPPORTED)")
  void fileTypeMismatchFails() {
    when(knowledgeFileClient.read(7L, KB, DOC)).thenReturn(file(SHA, SIZE, "MARKDOWN", "x"));

    orchestrator.process(job(), payload("TXT"));

    verifyFailed(IngestionJobFailureCategory.FILE_TYPE_UNSUPPORTED);
  }

  @Test
  @DisplayName("非 UTF-8 字节 → markFailed(FILE_DECODE_FAILED)")
  void decodeFailureFails() {
    // 0xC0 0xC1 是非法 UTF-8 前缀字节序列。
    when(knowledgeFileClient.read(7L, KB, DOC))
        .thenReturn(new KnowledgeFileRead(SIZE, SHA, "TXT", new byte[] {(byte) 0xC0, (byte) 0xC1}));

    orchestrator.process(job(), payload("TXT"));

    verifyFailed(IngestionJobFailureCategory.FILE_DECODE_FAILED);
  }

  @Test
  @DisplayName("gRPC 读取失败 → markFailed(FILE_READ_FAILED)")
  void readFailureFails() {
    when(knowledgeFileClient.read(7L, KB, DOC))
        .thenThrow(new KnowledgeFileReadException("read failed"));

    orchestrator.process(job(), payload("TXT"));

    verifyFailed(IngestionJobFailureCategory.FILE_READ_FAILED);
  }

  @Test
  @DisplayName("markProcessing 冲突 → 跳过处理，不读取文件")
  void markProcessingConflictSkipsProcessing() {
    doThrow(new IngestionJobConflictException(DOC, 1L, "conflict"))
        .when(ingestionJobService)
        .markProcessing(any());

    orchestrator.process(job(), payload("TXT"));

    verify(knowledgeFileClient, never()).read(anyLong(), anyLong(), anyLong());
    verify(chunkDao, never()).saveAll(anyList());
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<List<Chunk>> captureSavedChunks() {
    ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
    verify(chunkDao).saveAll(captor.capture());
    return captor;
  }

  private void verifyFailed(IngestionJobFailureCategory category) {
    ArgumentCaptor<IngestionJobFailureCategory> cat =
        ArgumentCaptor.forClass(IngestionJobFailureCategory.class);
    verify(ingestionJobService).markFailed(any(), cat.capture(), any());
    assertThat(cat.getValue()).isEqualTo(category);
  }
}
