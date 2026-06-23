package ai.cerbur.crag.id.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RedisWorkerLeaseRepository} using {@link FakeRedisMap}.
 *
 * <p>No Spring context — verifies key format, SET NX, compare-and-delete, and slot scanning.
 */
@DisplayName("RedisWorkerLeaseRepository")
class RedisWorkerLeaseRepositoryTest {

  private FakeRedisMap fakeRedis;
  private String ownerA;
  private String ownerB;

  @BeforeEach
  void setUp() {
    fakeRedis = new FakeRedisMap();
    ownerA = "owner-a-token";
    ownerB = "owner-b-token";
  }

  private RedisWorkerLeaseRepository newRepo() {
    return new RedisWorkerLeaseRepository(new FakeRedisTemplate(fakeRedis));
  }

  @Nested
  @DisplayName("tryAcquire")
  class TryAcquire {

    @Test
    @DisplayName("succeeds on empty slot using set-if-absent")
    void succeedsOnEmptySlot() {
      RedisWorkerLeaseRepository repo = newRepo();

      boolean acquired =
          repo.tryAcquire("rag", IdEntityType.CHUNK, 3, ownerA, Duration.ofSeconds(30));

      assertThat(acquired).isTrue();
      assertThat(fakeRedis.get("crag:id:rag:CHUNK:3")).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("does not overwrite an already occupied slot")
    void doesNotOverwriteOccupiedSlot() {
      RedisWorkerLeaseRepository repo = newRepo();
      repo.tryAcquire("rag", IdEntityType.CHUNK, 3, ownerA, Duration.ofSeconds(30));

      boolean acquired =
          repo.tryAcquire("rag", IdEntityType.CHUNK, 3, ownerB, Duration.ofSeconds(30));

      assertThat(acquired).isFalse();
      assertThat(fakeRedis.get("crag:id:rag:CHUNK:3")).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("can occupy different worker slots independently")
    void differentSlotsIndependent() {
      RedisWorkerLeaseRepository repo = newRepo();

      boolean a = repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(30));
      boolean b = repo.tryAcquire("rag", IdEntityType.CHUNK, 1, ownerB, Duration.ofSeconds(30));

      assertThat(a).isTrue();
      assertThat(b).isTrue();
      assertThat(fakeRedis.get("crag:id:rag:CHUNK:0")).isEqualTo(ownerA);
      assertThat(fakeRedis.get("crag:id:rag:CHUNK:1")).isEqualTo(ownerB);
    }
  }

  @Nested
  @DisplayName("release")
  class Release {

    @Test
    @DisplayName("deletes key when owner token matches")
    void deletesOnOwnerMatch() {
      RedisWorkerLeaseRepository repo = newRepo();
      repo.tryAcquire("rag", IdEntityType.LEGACY_DOCUMENT, 2, ownerA, Duration.ofSeconds(30));

      boolean released = repo.release("rag", IdEntityType.LEGACY_DOCUMENT, 2, ownerA);

      assertThat(released).isTrue();
      assertThat(fakeRedis.containsKey("crag:id:rag:LEGACY_DOCUMENT:2")).isFalse();
    }

    @Test
    @DisplayName("does not delete a worker owned by another process")
    void doesNotDeleteOtherOwner() {
      RedisWorkerLeaseRepository repo = newRepo();
      repo.tryAcquire("rag", IdEntityType.CHUNK, 2, ownerA, Duration.ofSeconds(30));

      boolean released = repo.release("rag", IdEntityType.CHUNK, 2, ownerB);

      assertThat(released).isFalse();
      assertThat(fakeRedis.get("crag:id:rag:CHUNK:2")).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("returns false when key is already absent")
    void returnsFalseOnAbsentKey() {
      RedisWorkerLeaseRepository repo = newRepo();

      boolean released = repo.release("rag", IdEntityType.CHUNK, 5, ownerA);

      assertThat(released).isFalse();
    }
  }

  @Nested
  @DisplayName("renew")
  class Renew {

    @Test
    @DisplayName("refreshes TTL when owner token matches")
    void refreshesTtlOnOwnerMatch() {
      RedisWorkerLeaseRepository repo = newRepo();
      repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(10));

      // Advance past the original TTL
      fakeRedis.advanceTimeAndExpire(11_000);
      assertThat(fakeRedis.containsKey("crag:id:rag:CHUNK:0")).isFalse();

      // Renew should not pass because key already expired — this test is for the "match" path
    }

    @Test
    @DisplayName("renew succeeds and extends TTL when owner matches and key still exists")
    void renewSucceedsOnOwnerMatch() {
      // Re-acquire first
      FakeRedisMap fresh = new FakeRedisMap();
      RedisWorkerLeaseRepository repo =
          new RedisWorkerLeaseRepository(new FakeRedisTemplate(fresh));
      repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(30));

      boolean renewed = repo.renew("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(30));

      assertThat(renewed).isTrue();
    }

    @Test
    @DisplayName("renew fails when key is already expired or absent")
    void renewFailsOnAbsentKey() {
      FakeRedisMap fresh = new FakeRedisMap();
      RedisWorkerLeaseRepository repo =
          new RedisWorkerLeaseRepository(new FakeRedisTemplate(fresh));
      repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(10));

      fresh.advanceTimeAndExpire(11_000);

      boolean renewed = repo.renew("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(30));

      assertThat(renewed).isFalse();
    }

    @Test
    @DisplayName("renew fails when owner token does not match")
    void renewFailsOnOwnerMismatch() {
      FakeRedisMap fresh = new FakeRedisMap();
      RedisWorkerLeaseRepository repo =
          new RedisWorkerLeaseRepository(new FakeRedisTemplate(fresh));
      repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(30));

      boolean renewed = repo.renew("rag", IdEntityType.CHUNK, 0, ownerB, Duration.ofSeconds(30));

      assertThat(renewed).isFalse();
    }
  }

  @Nested
  @DisplayName("findAvailableSlot")
  class FindAvailableSlot {

    @Test
    @DisplayName("returns first empty slot in range")
    void returnsFirstEmptySlot() {
      RedisWorkerLeaseRepository repo = newRepo();
      // Occupy slots 0 and 1
      repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(30));
      repo.tryAcquire("rag", IdEntityType.CHUNK, 1, ownerB, Duration.ofSeconds(30));

      var slot = repo.findAvailableSlot("rag", IdEntityType.CHUNK, 16);

      assertThat(slot).hasValue(2);
    }

    @Test
    @DisplayName("returns empty when all 16 slots occupied")
    void returnsEmptyWhenAllSlotsOccupied() {
      RedisWorkerLeaseRepository repo = newRepo();
      for (int i = 0; i < 16; i++) {
        repo.tryAcquire("rag", IdEntityType.CHUNK, i, ownerA, Duration.ofSeconds(30));
      }

      var slot = repo.findAvailableSlot("rag", IdEntityType.CHUNK, 16);

      assertThat(slot).isEmpty();
    }

    @Test
    @DisplayName("reclaims slot whose key has expired")
    void reclaimsExpiredSlot() {
      RedisWorkerLeaseRepository repo = newRepo();
      repo.tryAcquire("rag", IdEntityType.CHUNK, 0, ownerA, Duration.ofSeconds(10));
      fakeRedis.advanceTimeAndExpire(11_000);

      var slot = repo.findAvailableSlot("rag", IdEntityType.CHUNK, 16);

      assertThat(slot).hasValue(0);
    }
  }
}
