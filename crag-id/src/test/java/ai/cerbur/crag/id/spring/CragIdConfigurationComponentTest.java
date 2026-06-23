package ai.cerbur.crag.id.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.id.redis.RedisWorkerLease;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Component test for Spring configuration binding and health indicator integration.
 *
 * <p>Health indicator tests use Mockito mocks for {@link RedisWorkerLease} — no Redis connection is
 * needed.
 */
@DisplayName("CragIdConfiguration")
class CragIdConfigurationComponentTest {

  @Nested
  @DisplayName("configuration binding")
  @SpringBootTest(classes = TestCragIdApp.class)
  @TestPropertySource(
      properties = {
        "crag.id.service-domain=rag",
        "crag.id.required-entities=LEGACY_DOCUMENT,CHUNK",
        "crag.id.lease-ttl=30s",
        "crag.id.renew-interval=10s",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
      })
  class ConfigurationBinding {

    @Autowired private CragIdProperties properties;

    @Test
    @DisplayName("binds service domain and required entities")
    void bindsServiceDomainAndRequiredEntities() {
      assertThat(properties.getServiceDomain()).isEqualTo("rag");
      assertThat(properties.getRequiredEntities())
          .containsExactlyInAnyOrder(IdEntityType.LEGACY_DOCUMENT, IdEntityType.CHUNK);
    }

    @Test
    @DisplayName("binds TTL and renew interval with duration format")
    void bindsTtlAndRenewInterval() {
      assertThat(properties.getLeaseTtl().getSeconds()).isEqualTo(30);
      assertThat(properties.getRenewInterval().getSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("default rollback threshold is 5ms")
    void defaultRollbackThreshold() {
      assertThat(properties.getRollbackThresholdMillis()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("health indicator")
  class HealthIndicator {

    @Test
    @DisplayName("reports DOWN when required entity has no lease")
    void downWhenNoLease() {
      CragIdHealthIndicator.LeaseAccessor noLease = et -> null;
      CragIdHealthIndicator indicator =
          new CragIdHealthIndicator(noLease, Set.of(IdEntityType.LEGACY_DOCUMENT));

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails().get("cragId")).isNotNull();
    }

    @Test
    @DisplayName("reports DOWN when lease is not active")
    void downWhenLeaseInactive() {
      RedisWorkerLease inactiveLease = mock(RedisWorkerLease.class);
      when(inactiveLease.isActive()).thenReturn(false);
      when(inactiveLease.workerId()).thenReturn(7);

      CragIdHealthIndicator.LeaseAccessor accessor = et -> inactiveLease;
      CragIdHealthIndicator indicator =
          new CragIdHealthIndicator(accessor, Set.of(IdEntityType.LEGACY_DOCUMENT));

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("reports UP when all required entities have active leases")
    void upWhenAllActive() {
      RedisWorkerLease activeLease = mock(RedisWorkerLease.class);
      when(activeLease.isActive()).thenReturn(true);
      when(activeLease.workerId()).thenReturn(3);

      CragIdHealthIndicator.LeaseAccessor accessor = et -> activeLease;
      CragIdHealthIndicator indicator =
          new CragIdHealthIndicator(accessor, Set.of(IdEntityType.CHUNK));

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("reports UP when no required entities configured")
    void upWhenNoEntitiesRequired() {
      CragIdHealthIndicator.LeaseAccessor accessor = et -> null;
      CragIdHealthIndicator indicator = new CragIdHealthIndicator(accessor, Set.of());

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
  }
}
