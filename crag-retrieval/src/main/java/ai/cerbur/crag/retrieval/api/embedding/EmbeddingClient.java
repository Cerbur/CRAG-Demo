package ai.cerbur.crag.retrieval.api.embedding;

/**
 * EmbeddingClient 统一接口 —— 文本向量化.
 *
 * <p>实现：SidecarEmbeddingClient —— 调用 Sidecar Python 容器（FastAPI + gte-chinese-base）HTTP POST /embed.
 * 输入文本，返回 float[] 稠密向量（768 维）.
 *
 * @since 2026-06-10
 */
public interface EmbeddingClient {

  /**
   * 将文本转为稠密向量.
   *
   * @param text 输入文本
   * @return float[] 稠密向量（768 维）
   * @throws EmbeddingException 调用失败时抛出
   */
  float[] embed(String text);
}
