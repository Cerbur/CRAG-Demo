package ai.cerbur.crag.knowledge.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.cerbur.crag.event.api.OutboxEventStatus;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.event.jdbc.OutboxEventRecord;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseResult;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseService;
import ai.cerbur.crag.knowledge.dao.KnowledgeBaseDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * KnowledgeBaseCreatedOutboxWriter 组件测试（plan_21/21.3）。
 *
 * <p>H2 + 真实 schema-knowledge.sql（含事件序列与 outbox_event）：
 *
 * <ul>
 *   <li>成功路径：{@link KnowledgeBaseService#create} 同事务写 KB 业务行 + KNOWLEDGE_BASE_CREATED Outbox 行；
 *   <li>回滚路径：业务事务回滚时，KB 行与 Outbox 行一并回滚（同退），证明二者在同一物理事务内。
 * </ul>
 *
 * <p>使用 {@link DirtiesContext} 隔离本测试的应用上下文与 H2 状态，避免与其他 {@code @SpringBootTest} 残留数据相互影响。
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:schema-knowledge.sql",
      "spring.datasource.url=jdbc:h2:mem:kb_created_probe;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
    })
@DirtiesContext
@DisplayName("KnowledgeBaseCreated producer")
class KnowledgeBaseCreatedProducerTest {

  @Autowired private KnowledgeBaseService knowledgeBaseService;
  @Autowired private KbCreateRollbackProbe rollbackProbe;
  @Autowired private KnowledgeBaseDao knowledgeBaseDao;
  @Autowired private JdbcOutboxEventDao outboxDao;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("create 成功 → 同事务写 KNOWLEDGE_BASE_CREATED Outbox，状态 PENDING，payload 仅含安全字段")
  void createWritesKbCreatedOutboxSameTransaction() {
    KnowledgeBaseResult kb = knowledgeBaseService.create(701L, "kb-created", 9001L);

    assertThat(kb.knowledgeBaseId()).isPositive();
    assertThat(knowledgeBaseDao.findByIdAndTenant(kb.knowledgeBaseId(), 701L)).isPresent();

    Long eventId =
        jdbcTemplate.queryForObject(
            "SELECT MIN(event_id) FROM outbox_event WHERE event_type = 'KNOWLEDGE_BASE_CREATED' "
                + "AND resource_id = ?",
            Long.class,
            kb.knowledgeBaseId());
    assertThat(eventId).isNotNull();
    OutboxEventRecord record = outboxDao.findById(eventId);
    assertThat(record).isNotNull();
    assertThat(record.eventType()).isEqualTo("KNOWLEDGE_BASE_CREATED");
    assertThat(record.producer()).isEqualTo("knowledge-service");
    assertThat(record.resourceType()).isEqualTo("KNOWLEDGE_BASE");
    assertThat(record.resourceId()).isEqualTo(kb.knowledgeBaseId());
    assertThat(record.operationVersion()).isEqualTo(1L);
    assertThat(record.status()).isEqualTo(OutboxEventStatus.PENDING);
    assertThat(record.payload())
        .contains("tenantId", "knowledgeBaseId", "ownerUserId")
        .doesNotContain("name", "status");
  }

  @Test
  @DisplayName("业务事务回滚 → KB 行与 Outbox 行同退，二者都不持久化")
  void rollbackRollsBackBothBusinessRowAndOutbox() {
    // 探针在 @Transactional 内调用 create（同事务写 KB 业务行 + Outbox 行）随后抛异常，
    // 触发 Spring 事务回滚；事务回滚应同时撤销二者，证明它们在同一物理事务内。
    long tenantId = 702L;
    Throwable thrown =
        catchThrowable(() -> rollbackProbe.createThenRollback(tenantId, "kb-rollback", 9002L));
    assertThat(thrown)
        .isInstanceOf(KbCreateRollbackProbe.ForcedRollbackException.class)
        .hasMessageContaining("kbId=");

    // 从异常 message 提取 kbId（探针未返回结果，因为事务回滚抛出）。
    long kbId = extractKbId(thrown.getMessage());
    // 事务已回滚：KB 业务行不持久化。
    assertThat(knowledgeBaseDao.findByIdAndTenant(kbId, tenantId)).as("KB 业务行已回滚").isEmpty();
    // KNOWLEDGE_BASE_CREATED Outbox 行也不持久化。
    Integer outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'KNOWLEDGE_BASE_CREATED' "
                + "AND resource_id = ?",
            Integer.class,
            kbId);
    assertThat(outboxCount).as("KNOWLEDGE_BASE_CREATED Outbox 行已回滚").isZero();
  }

  private static long extractKbId(String message) {
    // message 格式："forced rollback after kb create, kbId=42"
    int eq = message.indexOf("kbId=");
    if (eq < 0) {
      throw new IllegalStateException("cannot extract kbId from: " + message);
    }
    return Long.parseLong(message.substring(eq + "kbId=".length()).trim());
  }
}
