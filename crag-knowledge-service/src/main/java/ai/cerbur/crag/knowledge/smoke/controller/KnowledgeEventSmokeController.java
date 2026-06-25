package ai.cerbur.crag.knowledge.smoke.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeEventRequest;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeEventResponse;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeEventStatusResponse;
import ai.cerbur.crag.knowledge.smoke.event.KnowledgeSmokeEventService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-only event diagnostics for the Knowledge service. Registered only under the {@code smoke}
 * profile; the default Knowledge service does not expose {@code /api/v1/smoke/**}.
 */
@RestController
@Profile("smoke")
@RequestMapping("/api/v1/smoke/events")
public class KnowledgeEventSmokeController {

  @Autowired private KnowledgeSmokeEventService service;

  /** Creates a smoke event in the Knowledge outbox. */
  @PostMapping
  public Response<KnowledgeSmokeEventResponse> create(
      @RequestBody @Valid KnowledgeSmokeEventRequest request) {
    return Response.success(
        service.createEvent(request.runId(), request.message(), request.failMode()));
  }

  /** Returns diagnostic summaries for all events in a run. */
  @GetMapping(params = "runId")
  public Response<List<KnowledgeSmokeEventStatusResponse>> findByRunId(@RequestParam String runId) {
    return Response.success(service.statusByRunId(runId));
  }

  /** Returns the diagnostic summary for a single event. */
  @GetMapping("/{eventId}")
  public Response<KnowledgeSmokeEventStatusResponse> findByEventId(@PathVariable String eventId) {
    return Response.success(service.statusByEventId(parseEventId(eventId)));
  }

  private static long parseEventId(String eventId) {
    try {
      return Long.parseLong(eventId);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("eventId must be a decimal string: " + eventId);
    }
  }
}
