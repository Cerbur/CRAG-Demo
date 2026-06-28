package ai.cerbur.crag.ingestion.consumer;

import ai.cerbur.crag.ingestion.head.IngestionHeadService;
import ai.cerbur.crag.ingestion.job.IngestionJobService;
import ai.cerbur.crag.ingestion.producer.RagIngestionStatusEventWriter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * RAG 事件消费组件测试专用窄上下文（Plan 19，Plan 21.4 起装配 IngestionHeadService）：装配 H2 + storage DAO +
 * IngestionJobService + IngestionHeadService + 状态事件写入器，不加载 gRPC / 检索 / LLM.
 *
 * <p>{@link DocUploadedEventHandler} 受 {@code smoke} Profile 限制，测试中以真实 {@link IngestionJobService}
 * 与 {@link IngestionHeadService} 构造，直接验证 outcome 映射、幂等建 Job 与 head 推进。真实 Redis Streams 的 Pending
 * reclaim / DLQ 由 Docker HTTP 回归证明（plan_19.7）.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("ai.cerbur.crag.storage")
@Import({
  IngestionJobService.class,
  RagIngestionStatusEventWriter.class,
  IngestionHeadService.class
})
class RagEventConsumerTestConfig {}
