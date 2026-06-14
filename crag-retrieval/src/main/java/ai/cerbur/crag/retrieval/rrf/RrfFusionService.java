package ai.cerbur.crag.retrieval.rrf;

import ai.cerbur.crag.storage.ChunkSearchResult;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * RRF 融合服务 —— 对 Sparse + Dense 两路 child chunk 检索结果做 Reciprocal Rank Fusion 并回表.
 *
 * 融合后通过 parent_chunk_id 回表获取完整 parent 上下文，交给下游 rerank.
 *
 * @since 2026-06-10
 */
@Component
public class RrfFusionService {

    /**
     * RRF 常数 k，防止单路 rank=1 导致分母过小.
     */
    private static final int RRF_K = 60;

    /**
     * 对两路 child chunk 结果执行 RRF 融合，回表取 parent chunk（骨架，plan_6 6.8 实现）.
     *
     * @param sparseResults Sparse 检索结果（child chunk 维度）
     * @param denseResults  Dense 检索结果（child chunk 维度）
     * @param topN          融合后保留数量
     * @return 空列表（6.8 实现后返回 RRF 融合结果）
     */
    public List<ChunkSearchResult> fuse(List<ChunkSearchResult> sparseResults,
                                         List<ChunkSearchResult> denseResults,
                                         int topN) {
        return Collections.emptyList();
    }
}
