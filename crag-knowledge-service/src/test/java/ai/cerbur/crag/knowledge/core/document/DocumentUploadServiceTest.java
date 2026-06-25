package ai.cerbur.crag.knowledge.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseNotFoundException;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.FileObjectDao;
import ai.cerbur.crag.knowledge.dao.KnowledgeBaseDao;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.entity.FileObjectEntity;
import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import ai.cerbur.crag.knowledge.filestore.LocalFileStore;
import ai.cerbur.crag.knowledge.filestore.StorageKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DocumentUploadService 纯单元测试：真实 filestore/policy/keyGen + Mock DAO，覆盖成功、知识库不存在、metadata 非法、内容校验失败
 * 并验证失败时不创建业务记录且清理临时文件。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentUploadService")
class DocumentUploadServiceTest {

  @Mock private KnowledgeBaseDao knowledgeBaseDao;
  @Mock private DocumentDao documentDao;
  @Mock private FileObjectDao fileObjectDao;

  @TempDir Path filestoreRoot;

  private DocumentUploadService service;

  @BeforeEach
  void setUp() {
    service = new DocumentUploadService();
    ReflectionTestUtils.setField(service, "knowledgeBaseDao", knowledgeBaseDao);
    ReflectionTestUtils.setField(service, "documentDao", documentDao);
    ReflectionTestUtils.setField(service, "fileObjectDao", fileObjectDao);
    ReflectionTestUtils.setField(
        service, "fileStore", new LocalFileStore(filestoreRoot.toString()));
    ReflectionTestUtils.setField(service, "storageKeyGenerator", new StorageKeyGenerator());
    ReflectionTestUtils.setField(service, "uploadPolicy", new DocumentUploadPolicy());
  }

  @Test
  @DisplayName("成功上传创建 Document 与 FileObject，sha256/大小/类型正确")
  void successfulUploadCreatesDocumentAndFileObject() {
    byte[] content = "hello knowledge".getBytes(StandardCharsets.UTF_8);
    DocumentEntity inserted = insertedDoc(42L);
    when(knowledgeBaseDao.findByIdAndTenant(anyLong(), anyLong()))
        .thenReturn(Optional.of(mock(KnowledgeBaseEntity.class)));
    when(documentDao.insert(any(DocumentEntity.class))).thenReturn(inserted);

    DocumentUploadCommand command =
        new DocumentUploadCommand(
            1L, 10L, 100L, "doc.txt", FileType.TXT, content.length, sha256(content));
    UploadHandle handle = service.begin(command);
    service.append(handle, content, 0, content.length);
    DocumentUploadResult result = service.complete(handle);

    assertThat(result.docId()).isEqualTo(42L);
    assertThat(result.sizeBytes()).isEqualTo(content.length);
    assertThat(result.sha256()).isEqualTo(sha256(content));
    assertThat(result.fileType()).isEqualTo(FileType.TXT);

    ArgumentCaptor<FileObjectEntity> fileCaptor = ArgumentCaptor.forClass(FileObjectEntity.class);
    verify(fileObjectDao).insert(fileCaptor.capture());
    assertThat(fileCaptor.getValue().getDocId()).isEqualTo(42L);
    assertThat(fileCaptor.getValue().getSizeBytes()).isEqualTo(content.length);
  }

  @Test
  @DisplayName("知识库不存在时 begin 抛 not found，不创建文件或业务记录")
  void beginRejectsUnknownKnowledgeBase() {
    when(knowledgeBaseDao.findByIdAndTenant(10L, 1L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.begin(
                    new DocumentUploadCommand(
                        1L, 10L, 100L, "doc.txt", FileType.TXT, 3L, sha256("abc"))))
        .isInstanceOf(KnowledgeBaseNotFoundException.class);

    verify(documentDao, never()).insert(any());
    verify(fileObjectDao, never()).insert(any());
  }

  @Test
  @DisplayName("metadata 非法时 begin 抛异常，不创建文件")
  void beginRejectsInvalidMetadata() {
    assertThatThrownBy(
            () ->
                service.begin(
                    new DocumentUploadCommand(
                        1L, 10L, 100L, "doc.exe", FileType.TXT, 1L, sha256("x"))))
        .isInstanceOf(IllegalArgumentException.class);

    verify(knowledgeBaseDao, never()).findByIdAndTenant(anyLong(), anyLong());
    verify(documentDao, never()).insert(any());
  }

  @Test
  @DisplayName("内容 sha256 不匹配时 complete 抛异常，不创建业务记录并清理临时文件")
  void contentValidationFailureCreatesNoRecordsAndCleansUp() throws Exception {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    when(knowledgeBaseDao.findByIdAndTenant(anyLong(), anyLong()))
        .thenReturn(Optional.of(mock(KnowledgeBaseEntity.class)));

    String wrongSha = "0".repeat(64);
    DocumentUploadCommand command =
        new DocumentUploadCommand(1L, 10L, 100L, "doc.txt", FileType.TXT, content.length, wrongSha);
    UploadHandle handle = service.begin(command);
    service.append(handle, content, 0, content.length);

    assertThatThrownBy(() -> service.complete(handle)).isInstanceOf(IllegalArgumentException.class);

    verify(documentDao, never()).insert(any());
    verify(fileObjectDao, never()).insert(any());
    assertThat(Files.walk(filestoreRoot).filter(p -> !p.equals(filestoreRoot)).count())
        .as("临时文件已清理")
        .isZero();
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static DocumentEntity insertedDoc(long docId) {
    DocumentEntity entity = mock(DocumentEntity.class);
    when(entity.getDocId()).thenReturn(docId);
    when(entity.getIngestionStatus()).thenReturn(DocumentEntity.INGESTION_STATUS_PENDING);
    when(entity.getOperationVersion()).thenReturn(DocumentEntity.INITIAL_OPERATION_VERSION);
    return entity;
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
