package ai.cerbur.crag.event.spring;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import ai.cerbur.crag.event.redis.DeadLetterPublisher;
import ai.cerbur.crag.event.redis.RedisStreamEventMapper;
import ai.cerbur.crag.event.redis.RedisStreamOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("EventAutoConfiguration")
class EventAutoConfigurationComponentTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EventAutoConfiguration.class))
          .withUserConfiguration(EventAutoConfigurationTestBeans.class)
          .withPropertyValues("crag.event.poll-interval=PT1H");

  @Test
  @DisplayName("registers the shared DAO, mapper, ops and DLQ beans when dependencies are present")
  void registersInfrastructureBeans() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(JdbcOutboxEventDao.class);
          assertThat(context).hasSingleBean(JdbcProcessedEventDao.class);
          assertThat(context).hasSingleBean(RedisStreamEventMapper.class);
          assertThat(context).hasSingleBean(RedisStreamOps.class);
          assertThat(context).hasSingleBean(DeadLetterPublisher.class);
        });
  }

  @Test
  @DisplayName("does not start schedulers or the health indicator when nothing is enabled")
  void defaultDisablesSchedulersAndHealth() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean("eventPublisherScheduler");
          assertThat(context).doesNotHaveBean("eventConsumerScheduler");
          assertThat(context).doesNotHaveBean("eventHealthIndicator");
        });
  }
}
