package ai.cerbur.crag.event.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("EventPublisherAutoConfiguration")
class EventPublisherAutoConfigurationComponentTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EventAutoConfiguration.class))
          .withUserConfiguration(EventAutoConfigurationTestBeans.class)
          .withPropertyValues("crag.event.poll-interval=PT1H");

  @Test
  @DisplayName("starts the publisher scheduler when crag.event.publisher.enabled=true")
  void startsPublisherWhenEnabled() {
    runner
        .withPropertyValues("crag.event.publisher.enabled=true")
        .run(
            context -> {
              assertThat(context).hasBean("eventPublisherScheduler");
            });
  }

  @Test
  @DisplayName("does not start the publisher scheduler by default")
  void noPublisherByDefault() {
    runner.run(context -> assertThat(context).doesNotHaveBean("eventPublisherScheduler"));
  }
}
