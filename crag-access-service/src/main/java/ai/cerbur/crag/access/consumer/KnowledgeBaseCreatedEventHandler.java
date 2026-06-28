package ai.cerbur.crag.access.consumer;

import ai.cerbur.crag.access.core.apikey.ApiKeyService;
import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Access 消费 {@code KNOWLEDGE_BASE_CREATED} 的 {@link EventHandler}（plan_21/21.2）。
 *
 * <p>双层幂等：crag-event 的 {@code processed_event}（{@code (consumer_name,
 * idempotency_key)}）保证同一投递不重复处理； 业务层 {@link ApiKeyService#ensureScope} 以 Scope
 * 主键（knowledge_base_id）+ 状态保证重复事件不重复建 Scope、 BLOCKED 终态不被复活、跨 Tenant 冲突安全失败。本类只负责 payload 解析、版本校验与
 * outcome 映射。
 *
 * <p>outcome 语义：
 *
 * <ul>
 *   <li>非 {@code KNOWLEDGE_BASE_CREATED} 事件（如 Access 自发布的 {@code API_KEY_INVALIDATED}）→ {@code
 *       success}，ACK 以保持消费组干净；
 *   <li>未知 payload version → {@code nonRetryableFailure}（安全 DLQ，不泄漏字段值）；
 *   <li>非法 payload → {@code nonRetryableFailure}（安全 DLQ）；
 *   <li>ensureScope 瞬时异常（如 DB 不可达）→ {@code retryableFailure}（留 Pending 等 reclaim）；
 *   <li>正常 → {@code success}。
 * </ul>
 *
 * <p>真实 Redis Streams 的 Pending reclaim / DLQ 与 processed_event 幂等门由 crag-event 的
 * RedisStreamEventConsumer 在启用 consumer 的环境执行；本 handler 只在 smoke Profile 注册，避免轻量上下文意外启动消费调度。
 */
@Component
@Profile("smoke")
public class KnowledgeBaseCreatedEventHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseCreatedEventHandler.class);

  /** 接受的事件类型。 */
  public static final String EVENT_TYPE = "KNOWLEDGE_BASE_CREATED";

  private static final Set<String> EVENT_TYPES = Set.of(EVENT_TYPE);

  private final ObjectMapper objectMapper;
  private final ApiKeyService apiKeyService;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;

  public KnowledgeBaseCreatedEventHandler(
      ObjectMapper objectMapper,
      ApiKeyService apiKeyService,
      @Value("${crag.event.knowledge-stream-key:crag:event:knowledge}") String streamKey,
      @Value("${crag.event.access-scope-group:access-scope}") String groupName,
      @Value("${crag.event.access-scope-consumer:access-scope-1}") String consumerName) {
    this.objectMapper = objectMapper;
    this.apiKeyService = apiKeyService;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.consumerName = consumerName;
  }

  @Override
  public String consumerName() {
    return consumerName;
  }

  @Override
  public String streamKey() {
    return streamKey;
  }

  @Override
  public String groupName() {
    return groupName;
  }

  @Override
  public Set<String> eventTypes() {
    return EVENT_TYPES;
  }

  @Override
  public EventHandlerResult handle(EventEnvelope envelope) {
    if (!EVENT_TYPES.contains(envelope.eventType())) {
      // 非 KNOWLEDGE_BASE_CREATED 事件：ACK 以保持消费组干净（例如 Access 自发布的 API_KEY_INVALIDATED）。
      return EventHandlerResult.success();
    }
    if (envelope.payloadVersion() != KnowledgeBaseCreatedPayload.SUPPORTED_PAYLOAD_VERSION) {
      log.warn(
          "KNOWLEDGE_BASE_CREATED payload version unsupported, dead-lettering — knowledgeBaseId(resourceId)={} version={}",
          envelope.resourceIdAsString(),
          envelope.payloadVersion());
      return EventHandlerResult.nonRetryableFailure(
          "unsupported KNOWLEDGE_BASE_CREATED payload version");
    }

    KnowledgeBaseCreatedPayload payload;
    try {
      payload = KnowledgeBaseCreatedPayload.parse(envelope.payload(), objectMapper);
    } catch (InvalidKnowledgeBaseCreatedPayloadException e) {
      log.warn(
          "KNOWLEDGE_BASE_CREATED payload invalid, dead-lettering — knowledgeBaseId(resourceId)={} reason={}",
          envelope.resourceIdAsString(),
          e.getMessage());
      return EventHandlerResult.nonRetryableFailure("invalid KNOWLEDGE_BASE_CREATED payload");
    }

    try {
      apiKeyService.ensureScope(
          payload.ownerUserId(), payload.tenantId(), payload.knowledgeBaseId());
      log.info(
          "KNOWLEDGE_BASE_CREATED ensured scope — tenantId={} knowledgeBaseId={}",
          payload.tenantId(),
          payload.knowledgeBaseId());
      return EventHandlerResult.success();
    } catch (RuntimeException e) {
      // 瞬时异常（如 DB 不可达）：不 ACK，留 Pending 由 reclaim 重新投递。
      log.warn(
          "KNOWLEDGE_BASE_CREATED ensureScope failed transiently, will retry — knowledgeBaseId={} reason={}",
          payload.knowledgeBaseId(),
          e.getMessage());
      return EventHandlerResult.retryableFailure("KNOWLEDGE_BASE_CREATED ensureScope failed");
    }
  }
}
