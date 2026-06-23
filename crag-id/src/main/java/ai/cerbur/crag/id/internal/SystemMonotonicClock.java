package ai.cerbur.crag.id.internal;

/**
 * Production {@link MonotonicClock} backed by {@link System#currentTimeMillis()} and {@link
 * Thread#sleep(long)}.
 */
public final class SystemMonotonicClock implements MonotonicClock {

  @Override
  public long currentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Override
  public void sleepUntil(long epochMillis) {
    long delay = epochMillis - System.currentTimeMillis();
    if (delay > 0) {
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
