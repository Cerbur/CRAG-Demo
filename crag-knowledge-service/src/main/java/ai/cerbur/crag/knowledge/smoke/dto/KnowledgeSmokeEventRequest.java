package ai.cerbur.crag.knowledge.smoke.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Smoke event creation request. Fields are intentionally controlled: {@code runId} is mandatory and
 * isolates Docker regression data; {@code message} is a short, non-sensitive test string; {@code
 * failMode} drives the smoke handler outcome. Arbitrary JSON payloads are not accepted.
 */
public record KnowledgeSmokeEventRequest(
    @NotBlank(message = "runId must not be blank") String runId,
    @Size(max = 256, message = "message must be at most 256 characters") String message,
    @NotNull(message = "failMode must not be null") KnowledgeSmokeFailMode failMode) {}
