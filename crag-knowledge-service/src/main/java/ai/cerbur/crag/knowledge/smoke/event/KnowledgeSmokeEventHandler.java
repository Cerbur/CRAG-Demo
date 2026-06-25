package ai.cerbur.crag.knowledge.smoke.event;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeFailMode;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Smoke {@link EventHandler} for {@code EVENT_SMOKE_CREATED}. It reads the {@code failMode} written
 * into the payload by the smoke service and returns a success, retryable or non-retryable result so
 * the publisher/consumer/DLQ paths can be exercised end to end.
 */
@Component
@Profile("smoke")
public class KnowledgeSmokeEventHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeSmokeEventHandler.class);
  private static final Set<String> EVENT_TYPES = Set.of("EVENT_SMOKE_CREATED");

  private final ObjectMapper objectMapper;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;

  public KnowledgeSmokeEventHandler(
      ObjectMapper objectMapper,
      @Value("${crag.event.stream-key:crag:event:knowledge}") String streamKey,
      @Value("${crag.event.group-name:knowledge-smoke}") String groupName,
      @Value("${crag.event.consumer.consumer-name:knowledge-smoke-1}") String consumerName) {
    this.objectMapper = objectMapper;
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
    KnowledgeSmokeFailMode failMode = parseFailMode(envelope.payload());
    log.debug(
        "Smoke handler invoked eventId={} runId={} failMode={}",
        envelope.eventIdAsString(),
        envelope.traceId(),
        failMode);
    return switch (failMode) {
      case NONE -> EventHandlerResult.success();
      case ALWAYS -> EventHandlerResult.retryableFailure("smoke always-fail mode");
      case NON_RETRYABLE -> EventHandlerResult.nonRetryableFailure("smoke non-retryable mode");
    };
  }

  private KnowledgeSmokeFailMode parseFailMode(String payload) {
    try {
      JsonNode node = objectMapper.readTree(payload);
      String raw = node.path("failMode").asText(KnowledgeSmokeFailMode.NONE.name());
      return KnowledgeSmokeFailMode.valueOf(raw);
    } catch (RuntimeException e) {
      log.debug("Smoke payload had no readable failMode, defaulting to NONE: {}", e.getMessage());
      return KnowledgeSmokeFailMode.NONE;
    }
  }
}
