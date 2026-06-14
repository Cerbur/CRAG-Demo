package ai.cerbur.crag.query.rerank;

import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 重排序服务 —— 对 RRF 融合回表后的 parent chunk 完整内容做语义重排序.
 *
 * Rerank 是结果进入 LLM 前的最后一步排序，确保传给 LLM 的 chunk 按语义相关度降序排列.
 *
 * @since 2026-06-10
 */
@Component
public class RerankService {

    /**
     * 对 chunk 列表做语义重排序（骨架，plan_3 实现）.
     *
     * @param query     用户问题
     * @param chunks    待排序的 chunk 内容列表
     * @return 空列表
     */
    public List<?> rerank(String query, List<?> chunks) {
        return Collections.emptyList();
    }
}
