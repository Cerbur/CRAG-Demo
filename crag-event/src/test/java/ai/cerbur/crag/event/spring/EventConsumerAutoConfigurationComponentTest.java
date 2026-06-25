package ai.cerbur.crag.event.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("EventConsumerAutoConfiguration")
class EventConsumerAutoConfigurationComponentTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EventAutoConfiguration.class))
          .withUserConfiguration(EventAutoConfigurationTestBeans.class)
          .withPropertyValues("crag.event.poll-interval=PT1H");

  @Test
  @DisplayName("starts the consumer scheduler when enabled even without a handler")
  void startsConsumerWithoutHandler() {
    runner
        .withPropertyValues("crag.event.consumer.enabled=true")
        .run(
            context -> {
              assertThat(context).hasBean("eventConsumerScheduler");
              assertThat(context).doesNotHaveBean(EventHandler.class);
            });
  }

  @Test
  @DisplayName("starts the consumer scheduler with a registered handler")
  void startsConsumerWithHandler() {
    runner
        .withPropertyValues("crag.event.consumer.enabled=true")
        .withUserConfiguration(HandlerConfig.class)
        .run(
            context -> {
              assertThat(context).hasBean("eventConsumerScheduler");
              assertThat(context).hasSingleBean(EventHandler.class);
            });
  }

  @Test
  @DisplayName("does not start the consumer scheduler by default")
  void noConsumerByDefault() {
    runner.run(context -> assertThat(context).doesNotHaveBean("eventConsumerScheduler"));
  }

  @Configuration
  static class HandlerConfig {
    @Bean
    EventHandler smokeHandler() {
      return new EventHandler() {
        @Override
        public String consumerName() {
          return "test-consumer";
        }

        @Override
        public String streamKey() {
          return "crag:event:test";
        }

        @Override
        public String groupName() {
          return "test-group";
        }

        @Override
        public Set<String> eventTypes() {
          return Set.of("EVENT_TEST");
        }

        @Override
        public EventHandlerResult handle(EventEnvelope envelope) {
          return EventHandlerResult.success();
        }
      };
    }
  }
}
