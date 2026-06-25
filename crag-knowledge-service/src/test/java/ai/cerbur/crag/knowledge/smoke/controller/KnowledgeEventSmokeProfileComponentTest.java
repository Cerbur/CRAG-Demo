package ai.cerbur.crag.knowledge.smoke.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.cerbur.crag.knowledge.smoke.event.KnowledgeSmokeEventHandler;
import ai.cerbur.crag.knowledge.smoke.event.KnowledgeSmokeEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the smoke beans are gated by the {@code smoke} profile: absent under the default profile
 * (so the default Knowledge service never exposes the smoke endpoints) and present when smoke is
 * active.
 */
@DisplayName("Knowledge smoke profile gating")
class KnowledgeEventSmokeProfileComponentTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withUserConfiguration(SmokeBeans.class);

  @Import({
    KnowledgeEventSmokeController.class,
    KnowledgeSmokeEventService.class,
    KnowledgeSmokeEventHandler.class
  })
  static class SmokeBeans {}

  @Test
  @DisplayName("default profile registers no smoke beans")
  void defaultProfileRegistersNoSmokeBeans() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(KnowledgeEventSmokeController.class);
          assertThat(context).doesNotHaveBean(KnowledgeSmokeEventService.class);
          assertThat(context).doesNotHaveBean(KnowledgeSmokeEventHandler.class);
        });
  }

  @Test
  @DisplayName("smoke profile registers the controller, service and handler")
  void smokeProfileRegistersSmokeBeans() {
    runner
        .withPropertyValues("spring.profiles.active=smoke")
        .run(
            context -> {
              assertThat(context).hasSingleBean(KnowledgeEventSmokeController.class);
              assertThat(context).hasSingleBean(KnowledgeSmokeEventService.class);
              assertThat(context).hasSingleBean(KnowledgeSmokeEventHandler.class);
            });
  }
}
