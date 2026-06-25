package ai.cerbur.crag.event.api;

/**
 * Lifecycle status of a {@code processed_event} row.
 *
 * <p>{@code PROCESSED} and {@code DEAD_LETTERED} are terminal; {@code FAILED} may later move to
 * {@code PROCESSED} (reclaim success) or {@code DEAD_LETTERED} (delivery exhausted). Terminal
 * states are never overwritten by the ordinary success path.
 */
public enum ProcessedEventStatus {
  PROCESSED,
  FAILED,
  DEAD_LETTERED;

  public boolean isTerminal() {
    return this == PROCESSED || this == DEAD_LETTERED;
  }
}
