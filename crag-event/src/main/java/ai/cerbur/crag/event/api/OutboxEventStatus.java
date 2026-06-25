package ai.cerbur.crag.event.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status of an {@code outbox_event} row.
 *
 * <p>Legal transitions model the publisher claim / publish / retry / dead-letter state machine:
 *
 * <pre>
 * PENDING -> PUBLISHING -> PUBLISHED
 *                       -> RETRY_WAIT -> PUBLISHING
 *                                     -> DEAD
 *                       -> DEAD
 * </pre>
 *
 * {@code PUBLISHED} and {@code DEAD} are terminal.
 */
public enum OutboxEventStatus {
  PENDING,
  PUBLISHING,
  PUBLISHED,
  RETRY_WAIT,
  DEAD;

  /** Whether this status may transition to {@code target} following the publisher state machine. */
  public boolean canTransitionTo(OutboxEventStatus target) {
    return legalTargets().contains(target);
  }

  /**
   * Asserts a legal transition and returns the target, otherwise throws to surface state-machine
   * violations early.
   */
  public OutboxEventStatus requireTransitionTo(OutboxEventStatus target) {
    if (!canTransitionTo(target)) {
      throw new IllegalStateException("Illegal outbox transition: " + this + " -> " + target);
    }
    return target;
  }

  public boolean isTerminal() {
    return this == PUBLISHED || this == DEAD;
  }

  private Set<OutboxEventStatus> legalTargets() {
    return switch (this) {
      case PENDING -> EnumSet.of(PUBLISHING);
      case PUBLISHING -> EnumSet.of(PUBLISHED, RETRY_WAIT, DEAD);
      case RETRY_WAIT -> EnumSet.of(PUBLISHING, DEAD);
      case PUBLISHED, DEAD -> EnumSet.noneOf(OutboxEventStatus.class);
    };
  }
}
