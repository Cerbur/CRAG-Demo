package ai.cerbur.crag.knowledge.smoke.dto;

/** Controlled failure mode for smoke events, driving the smoke handler's outcome. */
public enum KnowledgeSmokeFailMode {
  NONE,
  ALWAYS,
  NON_RETRYABLE
}
