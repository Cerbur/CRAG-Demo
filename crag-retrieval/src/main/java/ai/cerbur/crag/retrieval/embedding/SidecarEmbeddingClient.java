package ai.cerbur.crag.retrieval.embedding;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Sidecar Embedding HTTP 客户端 —— 调用 Sidecar Python FastAPI /embed 端点.
 *
 * <p>协议：POST /embed，body: {"text": "..."} → {"embedding": [0.1, 0.2, ...]}.
 * 模型：gte-chinese-base，输出维度 768. 异常时抛出 EmbeddingException，由上层 Cron 捕获并标记 chunk dense_status =
 * FAILED.
 *
 * @since 2026-06-13
 */
@Service
public class SidecarEmbeddingClient implements EmbeddingClient {

  private static final Logger log = LoggerFactory.getLogger(SidecarEmbeddingClient.class);

  /** Sidecar 服务地址，默认 http://localhost:8001. */
  @Value("${crag.embedding.sidecar-url:http://localhost:8001}")
  private String sidecarUrl;

  /** 连接超时，默认 5 秒. */
  @Value("${crag.embedding.connect-timeout:5s}")
  private Duration connectTimeout;

  /** 读取超时，默认 30 秒（大文本 embedding 可能较慢）. */
  @Value("${crag.embedding.read-timeout:30s}")
  private Duration readTimeout;

  /** Embedding 请求路径，默认 /embed. */
  @Value("${crag.embedding.embed-path:/embed}")
  private String embedPath;

  /** RestClient 实例，@PostConstruct 时构造. */
  private RestClient restClient;

  /** 初始化 RestClient，配置 baseUrl 和超时参数. */
  @PostConstruct
  void init() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);

    this.restClient =
        RestClient.builder().baseUrl(sidecarUrl).requestFactory(requestFactory).build();

    log.info(
        "SidecarEmbeddingClient initialized — baseUrl={}, connectTimeout={}, readTimeout={}",
        sidecarUrl,
        connectTimeout,
        readTimeout);
  }

  /**
   * 将文本转为稠密向量.
   *
   * @param text 输入文本
   * @return float[] 稠密向量（768 维）
   * @throws EmbeddingException 调用失败时抛出，由上层 Cron 捕获并标记 failed
   */
  @Override
  public float[] embed(String text) {
    log.debug("Sending embed request, text length={}", text.length());

    try {
      // POST {sidecarUrl}/embed，body: {"text": text}
      EmbedResponse response =
          restClient
              .post()
              .uri(embedPath)
              .body(Map.of("text", text))
              .retrieve()
              .body(EmbedResponse.class);

      if (response == null || response.embedding() == null || response.embedding().length == 0) {
        throw new EmbeddingException("Sidecar returned empty embedding vector");
      }

      // double[] → float[]：pgvector 存储使用 float 精度，768 维足够
      double[] raw = response.embedding();
      float[] vector = new float[raw.length];
      for (int i = 0; i < raw.length; i++) {
        vector[i] = (float) raw[i];
      }

      log.debug("Embedding success — dimension={}", vector.length);
      return vector;
    } catch (EmbeddingException e) {
      // 已为 EmbeddingException，直接上抛
      throw e;
    } catch (ResourceAccessException e) {
      // 连接超时 / 拒绝连接 → 包装为 EmbeddingException
      throw new EmbeddingException(
          "Sidecar connection failed (url=" + sidecarUrl + "): " + e.getMessage(), e);
    } catch (Exception e) {
      // HTTP 4xx/5xx / JSON 解析失败 → 包装为 EmbeddingException
      throw new EmbeddingException("Embedding request failed: " + e.getMessage(), e);
    }
  }

  /**
   * Sidecar /embed 端点响应体 —— Jackson 自动将 JSON 数组反序列化为 double[].
   *
   * @param embedding 稠密向量（768 维）
   */
  private record EmbedResponse(double[] embedding) {}
}
