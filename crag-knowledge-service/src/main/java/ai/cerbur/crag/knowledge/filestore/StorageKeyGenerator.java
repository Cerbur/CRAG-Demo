package ai.cerbur.crag.knowledge.filestore;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 生成文件 storage key。
 *
 * <p>key 由租户、知识库与随机 ID 组成，<strong>不包含原始文件名</strong>；原始文件名仅作展示，不参与存储路径拼接。
 */
@Component
public class StorageKeyGenerator {

  /**
   * 生成形如 {@code <tenantId>/<knowledgeBaseId>/<uuid>} 的 storage key。
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @return 不含原始文件名的 storage key
   */
  public String generate(long tenantId, long knowledgeBaseId) {
    return tenantId + "/" + knowledgeBaseId + "/" + UUID.randomUUID();
  }
}
