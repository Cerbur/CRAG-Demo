package ai.cerbur.crag.id.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.id.api.CragIdParser;
import ai.cerbur.crag.id.api.IdEntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SnowflakeSequence")
class SnowflakeSequenceTest {

  private static final long EPOCH_MILLIS = SnowflakeLayout.EPOCH_MILLIS;
  private static final IdEntityType ENTITY = IdEntityType.LEGACY_DOCUMENT;

  private final FakeMonotonicClock clock = new FakeMonotonicClock(EPOCH_MILLIS + 100_000L);
  private final SnowflakeLayout layout = new SnowflakeLayout();

  private SnowflakeSequence createSequence() {
    return new SnowflakeSequence(0, ENTITY, layout, clock, 5L);
  }

  @AfterEach
  void tearDown() {
    clock.releaseSleeps();
  }

  @Nested
  @DisplayName("sequence within same millisecond")
  class SameMillisecond {

    @Test
    @DisplayName("increments from 0 to 1023")
    void incrementsZeroTo1023() {
      var seq = createSequence();

      for (int i = 0; i < 1024; i++) {
        long id = seq.nextId();
        CragIdParser.CragIdParts parts = layout.decode(id);
        assertThat(parts.sequence()).isEqualTo(i);
      }
    }

    @Test
    @DisplayName("overflow waits for next millisecond")
    void overflowWaitsForNextMillisecond() {
      var seq = createSequence();

      // Exhaust sequence 0..1023
      for (int i = 0; i < 1024; i++) {
        seq.nextId();
      }

      // Next call should overflow, wait, then produce sequence 0 at next ms
      clock.advanceTo(clock.currentTimeMillis() + 1);
      long nextId = seq.nextId();
      CragIdParser.CragIdParts parts = layout.decode(nextId);
      assertThat(parts.sequence()).isEqualTo(0);
      assertThat(parts.timestamp().toEpochMilli()).isEqualTo(clock.currentTimeMillis());
    }
  }

  @Nested
  @DisplayName("clock rollback handling")
  class ClockRollback {

    @Test
    @DisplayName("small rollback up to threshold waits and recovers")
    void smallRollbackWaits() {
      var seq = createSequence();
      long firstId = seq.nextId();
      long firstTimestamp = layout.decode(firstId).timestamp().toEpochMilli();

      // Simulate 3ms rollback (within 5ms threshold)
      clock.setCurrentTimeMillis(firstTimestamp - 3);

      // Should wait (sleep until firstTimestamp + 1) then produce next id
      long recoveredId = seq.nextId();
      CragIdParser.CragIdParts parts = layout.decode(recoveredId);
      assertThat(parts.timestamp().toEpochMilli()).isGreaterThanOrEqualTo(firstTimestamp);
    }

    @Test
    @DisplayName("large rollback throws ClockRollbackException")
    void largeRollbackThrows() {
      var seq = createSequence();
      long firstId = seq.nextId();
      long firstTimestamp = layout.decode(firstId).timestamp().toEpochMilli();

      // Simulate 10ms rollback (beyond 5ms threshold)
      clock.setCurrentTimeMillis(firstTimestamp - 10);

      assertThatThrownBy(seq::nextId).isInstanceOf(ClockRollbackException.class);
    }

    @Test
    @DisplayName("rollback exception carries rollback amount and last timestamp")
    void rollbackExceptionCarriesDetails() {
      var seq = createSequence();
      long firstId = seq.nextId();
      long firstTimestamp = layout.decode(firstId).timestamp().toEpochMilli();
      long rolledBackTime = firstTimestamp - 10;
      clock.setCurrentTimeMillis(rolledBackTime);

      assertThatThrownBy(seq::nextId)
          .isInstanceOf(ClockRollbackException.class)
          .matches(e -> ((ClockRollbackException) e).getLastTimestampMillis() == firstTimestamp);
    }
  }

  @Nested
  @DisplayName("clock moves forward normally")
  class ClockForward {

    @Test
    @DisplayName("new millisecond resets sequence to 0")
    void newMillisecondResetsSequence() {
      var seq = createSequence();

      // Produce a few ids in first millisecond
      seq.nextId();
      seq.nextId();
      seq.nextId();

      // Advance clock
      clock.advanceTo(clock.currentTimeMillis() + 1);

      long id = seq.nextId();
      CragIdParser.CragIdParts parts = layout.decode(id);
      assertThat(parts.sequence()).isEqualTo(0);
      assertThat(parts.timestamp().toEpochMilli()).isEqualTo(clock.currentTimeMillis());
    }
  }

  /** Controllable clock for testing sequence and rollback behavior. */
  static class FakeMonotonicClock implements MonotonicClock {
    private volatile long currentMillis;
    private volatile boolean sleeping;

    FakeMonotonicClock(long initialMillis) {
      this.currentMillis = initialMillis;
    }

    @Override
    public long currentTimeMillis() {
      return currentMillis;
    }

    @Override
    public void sleepUntil(long epochMillis) {
      sleeping = true;
      if (epochMillis > currentMillis) {
        currentMillis = epochMillis;
      }
    }

    void setCurrentTimeMillis(long millis) {
      this.currentMillis = millis;
    }

    void advanceTo(long millis) {
      this.currentMillis = millis;
    }

    void releaseSleeps() {
      sleeping = false;
    }
  }
}
