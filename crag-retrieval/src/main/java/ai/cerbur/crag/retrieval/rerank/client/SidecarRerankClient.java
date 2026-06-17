package ai.cerbur.crag.retrieval.rerank.client;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Sidecar Rerank HTTP 客户端 —— 调用 Sidecar Python FastAPI /rerank 端点.
 *
 * 协议：POST /rerank，body: {"query": "...", "documents": [...]} → {"results": [{"index": 0, "score": 0.95}, ...]}.
 * 模型：bge-reranker-v2-m3 (CrossEncoder).
 * 异常时记录日志并返回空结果，由上层 RerankService 降级回退.
 *
 * @since 2026-06-15
 */
@Service
public class SidecarRerankClient implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(SidecarRerankClient.class);

    /**
     * Sidecar 服务地址，默认 http://localhost:8001.
     */
    @Value("${crag.rerank.sidecar-url:http://localhost:8001}")
    private String sidecarUrl;

    /**
     * 连接超时，默认 5 秒.
     */
    @Value("${crag.rerank.connect-timeout:5s}")
    private Duration connectTimeout;

    /**
     * 读取超时，默认 30 秒.
     */
    @Value("${crag.rerank.read-timeout:30s}")
    private Duration readTimeout;

    /**
     * Rerank 请求路径，默认 /rerank.
     */
    @Value("${crag.rerank.rerank-path:/rerank}")
    private String rerankPath;

    /**
     * RestClient 实例，@PostConstruct 时构造.
     */
    private RestClient restClient;

    /**
     * 初始化 RestClient，配置 baseUrl 和超时参数.
     */
    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
            .baseUrl(sidecarUrl)
            .requestFactory(requestFactory)
            .build();

        log.info("SidecarRerankClient initialized — baseUrl={}, connectTimeout={}, readTimeout={}",
            sidecarUrl, connectTimeout, readTimeout);
    }

    /**
     * 对候选文档做语义重排序.
     *
     * @param query     用户问题
     * @param documents 候选文档列表
     * @return 按 score 降序排列的 rerank 结果；失败时返回空列表
     */
    @Override
    public List<RerankResult> rerank(String query, List<String> documents) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        log.debug("Sending rerank request — query length={}, document count={}", query.length(), documents.size());

        try {
            RerankResponse response = restClient.post()
                .uri(rerankPath)
                .body(Map.of("query", query, "documents", documents))
                .retrieve()
                .body(RerankResponse.class);

            if (response == null || response.results() == null) {
                log.warn("Sidecar returned empty rerank response");
                return Collections.emptyList();
            }

            log.debug("Rerank success — result count={}", response.results().size());
            return response.results().stream()
                .map(r -> new RerankResult(r.index(), (float) r.score()))
                .toList();
        } catch (ResourceAccessException e) {
            log.error("Sidecar rerank connection failed (url={}): {}", sidecarUrl, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Rerank request failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Sidecar /rerank 端点响应体.
     *
     * @param results rerank 结果列表，按 score 降序
     */
    private record RerankResponse(List<RerankResultItem> results) {}

    /**
     * Sidecar /rerank 单条结果.
     *
     * @param index 文档在输入列表中的原始位置
     * @param score 语义相关度分数
     */
    private record RerankResultItem(int index, double score) {}
}
