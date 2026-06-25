package ai.cerbur.crag.event.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Guards the {@link EventAutoConfiguration} auto-configuration ordering invariant.
 *
 * <p>The event beans use {@code @ConditionalOnBean} on framework-provided types ({@code
 * DataSource}, {@code JdbcTemplate}, {@code StringRedisTemplate}, {@code MeterRegistry}). Those
 * beans are created by other auto-configurations, so this class must evaluate <em>after</em> them;
 * otherwise every condition evaluates against an empty registry and the whole closed loop is
 * silently skipped — exactly the regression surfaced by the Docker HTTP smoke run where events
 * stayed {@code PENDING}. The sibling {@code *AutoConfigurationComponentTest} classes cannot catch
 * this because they register the infra beans as user beans, which always precede auto-configuration
 * evaluation. This test pins the ordering directly so the invariant survives refactors.
 */
@DisplayName("EventAutoConfiguration ordering")
class EventAutoConfigurationOrderingTest {

  @Test
  @DisplayName("is ordered after the framework infra auto-configurations")
  void orderedAfterInfraAutoConfigurations() {
    AutoConfiguration autoConfig =
        EventAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
    assertThat(autoConfig).as("@AutoConfiguration present").isNotNull();
    Set<String> after = Arrays.stream(autoConfig.afterName()).collect(Collectors.toSet());

    assertThat(after)
        .as("afterName must cover the auto-configurations that create the @ConditionalOnBean beans")
        .contains(
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration");
  }
}
