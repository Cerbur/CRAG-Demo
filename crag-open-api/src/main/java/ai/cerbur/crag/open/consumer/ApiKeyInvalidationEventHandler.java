package ai.cerbur.crag.open.consumer;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.open.authcache.ApiKeyAuthCache;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Open 消费 {@code API_KEY_INVALIDATED} 的 {@link EventHandler}（plan_21/21.10）。
 *
 * <p><strong>天然幂等</strong>：重复失效只重复 eviction（缓存是临时状态，进程重启后为空）。因此使用 {@code
 * EphemeralRedisStreamConsumer}（无 JDBC {@code processed_event}），不引入数据库。
 *
 * <p>定向 eviction：
 *
 * <ul>
 *   <li>{@code resourceType=API_KEY}（单 Key disable/rotate/revoke）→ {@link
 *       ApiKeyAuthCache#evictByApiKeyId(long)} + 记录版本水位；
 *   <li>{@code resourceType=API_KEY_SCOPE}（Scope BLOCKED）→ {@link
 *       ApiKeyAuthCache#evictByKnowledgeBaseId(long)} + 记录该 KB 下 Key 的版本水位。
 * </ul>
 *
 * <p>outcome 语义：
 *
 * <ul>
 *   <li>非 {@code API_KEY_INVALIDATED} 事件 → {@code success}（ACK，保持消费组干净）；
 *   <li>未知 payload version → {@code nonRetryableFailure}（安全 DLQ）；
 *   <li>非法 payload → {@code nonRetryableFailure}（安全 DLQ）；
 *   <li>正常 eviction → {@code success}。
 * </ul>
 */
@Component
public class ApiKeyInvalidationEventHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyInvalidationEventHandler.class);

  public static final String EVENT_TYPE = "API_KEY_INVALIDATED";
  private static final Set<String> EVENT_TYPES = Set.of(EVENT_TYPE);

  private final ObjectMapper objectMapper;
  private final ApiKeyAuthCache cache;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;

  @Autowired
  public ApiKeyInvalidationEventHandler(
      ObjectMapper objectMapper,
      ApiKeyAuthCache cache,
      @Value("${crag.event.access-stream-key:crag:event:access}") String streamKey,
      @Value("${crag.event.open-invalidation-group:open-invalidation}") String groupName,
      @Value("${crag.event.open-invalidation-consumer:open-invalidation-1}") String consumerName) {
    this.objectMapper = objectMapper;
    this.cache = cache;
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
      return EventHandlerResult.success();
    }
    if (envelope.payloadVersion() != ApiKeyInvalidationPayload.SUPPORTED_PAYLOAD_VERSION) {
      log.warn(
          "API_KEY_INVALIDATED payload version unsupported, dead-lettering — resourceId={} version={}",
          envelope.resourceIdAsString(),
          envelope.payloadVersion());
      return EventHandlerResult.nonRetryableFailure(
          "unsupported API_KEY_INVALIDATED payload version");
    }

    ApiKeyInvalidationPayload payload;
    try {
      payload = ApiKeyInvalidationPayload.parse(envelope.payload(), objectMapper);
    } catch (InvalidApiKeyInvalidationPayloadException e) {
      log.warn(
          "API_KEY_INVALIDATED payload invalid, dead-lettering — resourceId={} reason={}",
          envelope.resourceIdAsString(),
          e.getMessage());
      return EventHandlerResult.nonRetryableFailure("invalid API_KEY_INVALIDATED payload");
    }

    // 版本水位记录（event-before-put 竞态保护）：resourceVersion 即 Key 或 Scope 的版本。
    if (ApiKeyInvalidationPayload.RESOURCE_API_KEY.equals(payload.resourceType())) {
      cache.evictByApiKeyId(payload.resourceId());
      cache.observeInvalidation(payload.resourceId(), payload.resourceVersion(), 0L);
      log.info(
          "API_KEY_INVALIDATED evicted by apiKeyId — apiKeyId={} version={} action={}",
          payload.resourceId(),
          payload.resourceVersion(),
          payload.action());
    } else if (ApiKeyInvalidationPayload.RESOURCE_API_KEY_SCOPE.equals(payload.resourceType())) {
      cache.evictByKnowledgeBaseId(payload.knowledgeBaseId());
      // Scope 事件携带的 resourceVersion 是 scopeVersion；keyVersion 水位不清零（无法从 Scope 事件得知）
      cache.observeInvalidation(payload.resourceId(), 0L, payload.resourceVersion());
      log.info(
          "API_KEY_INVALIDATED evicted by knowledgeBaseId — knowledgeBaseId={} scopeVersion={} action={}",
          payload.knowledgeBaseId(),
          payload.resourceVersion(),
          payload.action());
    } else {
      log.warn(
          "API_KEY_INVALIDATED unknown resourceType, dead-lettering — resourceType={} resourceId={}",
          payload.resourceType(),
          payload.resourceId());
      return EventHandlerResult.nonRetryableFailure("unknown API_KEY_INVALIDATED resourceType");
    }

    return EventHandlerResult.success();
  }
}
