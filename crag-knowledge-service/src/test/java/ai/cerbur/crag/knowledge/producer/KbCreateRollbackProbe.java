package ai.cerbur.crag.knowledge.producer;

import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseResult;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * plan_21/21.3 回滚测试探针：在 {@code @Transactional} 内调用 {@link KnowledgeBaseService#create}（同事务写 KB 业务行
 * + KNOWLEDGE_BASE_CREATED Outbox 行），随后抛出异常强制事务回滚。用于证明二者在同一物理事务内。
 *
 * <p>仅在测试上下文注册（位于 {@code src/test}），不进入生产代码。
 */
@Service
public class KbCreateRollbackProbe {

  @Autowired private KnowledgeBaseService knowledgeBaseService;

  /** 创建 KB 后立即抛出异常；事务回滚应同时撤销 KB 业务行与 Outbox 行。 */
  @Transactional
  public KnowledgeBaseResult createThenRollback(long tenantId, String name, long createdByUserId) {
    KnowledgeBaseResult kb = knowledgeBaseService.create(tenantId, name, createdByUserId);
    throw new ForcedRollbackException(
        "forced rollback after kb create, kbId=" + kb.knowledgeBaseId());
  }

  /** 探针专用异常，便于测试捕获并携带 kbId。 */
  public static final class ForcedRollbackException extends RuntimeException {
    public ForcedRollbackException(String message) {
      super(message);
    }
  }
}
