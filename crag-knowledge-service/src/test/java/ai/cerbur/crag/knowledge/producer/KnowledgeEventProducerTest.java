package ai.cerbur.crag.knowledge.producer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.OutboxEventStatus;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.event.jdbc.OutboxEventRecord;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.core.document.FileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * DocUploadedOutboxWriter 组件测试：H2 + 真实 schema-knowledge.sql（含事件序列），验证写入的 DOC_UPLOADED Outbox 行字段与安全
 * payload。
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:schema-knowledge.sql"
    })
@DisplayName("DocUploadedOutboxWriter")
class KnowledgeEventProducerTest {

  @Autowired private DocUploadedOutboxWriter writer;
  @Autowired private JdbcOutboxEventDao outboxDao;

  @Test
  @DisplayName("写入 DOC_UPLOADED Outbox，状态 PENDING，payload 仅含安全字段")
  void writesDocUploadedOutboxWithSafePayload() {
    DocumentResult doc =
        new DocumentResult(
            100L,
            77L,
            10L,
            200L,
            "doc.txt",
            FileType.TXT,
            5L,
            "abc123",
            "PENDING",
            1L,
            0L,
            0L,
            0,
            null,
            null,
            null,
            null,
            null,
            null);

    long eventId = writer.write(doc);

    OutboxEventRecord record = outboxDao.findById(eventId);
    assertThat(record).isNotNull();
    assertThat(record.eventType()).isEqualTo("DOC_UPLOADED");
    assertThat(record.producer()).isEqualTo("knowledge-service");
    assertThat(record.resourceType()).isEqualTo("DOCUMENT");
    assertThat(record.resourceId()).isEqualTo(100L);
    assertThat(record.operationVersion()).isEqualTo(1L);
    assertThat(record.status()).isEqualTo(OutboxEventStatus.PENDING);
    assertThat(record.payload())
        .contains("docId", "fileType", "sha256")
        .doesNotContain("storageKey", "path", "content");
  }
}
