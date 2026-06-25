package ai.cerbur.crag.event.spring;

import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import ai.cerbur.crag.event.jdbc.OutboxBackoffPolicy;
import ai.cerbur.crag.event.jdbc.OutboxClaim;
import ai.cerbur.crag.event.jdbc.OutboxPublisherService;
import ai.cerbur.crag.event.redis.DeadLetterPublisher;
import ai.cerbur.crag.event.redis.RedisPendingReclaimer;
import ai.cerbur.crag.event.redis.RedisStreamEventConsumer;
import ai.cerbur.crag.event.redis.RedisStreamEventMapper;
import ai.cerbur.crag.event.redis.RedisStreamEventPublisher;
import ai.cerbur.crag.event.redis.RedisStreamOps;
import ai.cerbur.crag.event.redis.RedisTemplateStreamOps;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring Boot auto-configuration for the {@code crag-event} infrastructure.
 *
 * <p>Adding the {@code crag-event} dependency alone starts nothing. A service opts in by enabling
 * the publisher and/or consumer under {@code crag.event.*}. The publisher and consumer run on
 * dedicated single-thread schedulers; a consumer with no registered {@link EventHandler} starts but
 * does not poll, logging once per tick. Real Redis and PostgreSQL behaviour is exercised by the
 * Docker HTTP regressions.
 */
@AutoConfiguration
@EnableConfigurationProperties(EventProperties.class)
public class EventAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(EventAutoConfiguration.class);
  private static final String PUBLISHER_NAME = "crag-event-publisher";

  @Bean
  @ConditionalOnBean({DataSource.class, JdbcTemplate.class})
  JdbcOutboxEventDao jdbcOutboxEventDao(JdbcTemplate jdbcTemplate) {
    return new JdbcOutboxEventDao(jdbcTemplate);
  }

  @Bean
  @ConditionalOnBean({DataSource.class, JdbcTemplate.class})
  JdbcProcessedEventDao jdbcProcessedEventDao(JdbcTemplate jdbcTemplate) {
    return new JdbcProcessedEventDao(jdbcTemplate);
  }

  @Bean
  RedisStreamEventMapper redisStreamEventMapper() {
    return new RedisStreamEventMapper();
  }

  @Bean
  @ConditionalOnBean(StringRedisTemplate.class)
  RedisStreamOps redisStreamOps(StringRedisTemplate redisTemplate) {
    return new RedisTemplateStreamOps(redisTemplate);
  }

  @Bean
  @ConditionalOnBean(RedisStreamOps.class)
  DeadLetterPublisher deadLetterPublisher(
      RedisStreamOps ops, RedisStreamEventMapper mapper, EventProperties properties) {
    return new DeadLetterPublisher(ops, mapper, properties.getDlqStreamKey());
  }

  @Bean
  @ConditionalOnBean({MeterRegistry.class, JdbcTemplate.class})
  EventMetrics eventMetrics(JdbcTemplate jdbcTemplate) {
    return new EventMetrics(jdbcTemplate);
  }

  @Bean(name = "eventPublisherScheduler", destroyMethod = "shutdown")
  @ConditionalOnProperty(prefix = "crag.event.publisher", name = "enabled", havingValue = "true")
  @ConditionalOnBean({JdbcOutboxEventDao.class, RedisStreamOps.class})
  ThreadPoolTaskScheduler eventPublisherScheduler(
      JdbcOutboxEventDao outboxDao,
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      EventProperties properties) {
    OutboxBackoffPolicy backoff =
        new OutboxBackoffPolicy(
            properties.getBackoff().getInitial(), properties.getBackoff().getMax());
    OutboxPublisherService service =
        new OutboxPublisherService(
            outboxDao,
            backoff,
            properties.getPublisher().getMaxAttempts(),
            properties.getPublisher().getClaimDuration());
    RedisStreamEventPublisher action =
        new RedisStreamEventPublisher(ops, mapper, properties.getStreamKey());
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("crag-event-pub-");
    scheduler.initialize();
    scheduler.scheduleAtFixedRate(
        () -> {
          for (OutboxClaim claim : service.claimBatch(PUBLISHER_NAME, properties.getBatchSize())) {
            service.publish(claim, action);
          }
        },
        properties.getPollInterval());
    log.info(
        "Event publisher scheduler started for stream {} (poll {})",
        properties.getStreamKey(),
        properties.getPollInterval());
    return scheduler;
  }

  @Bean(name = "eventConsumerScheduler", destroyMethod = "shutdown")
  @ConditionalOnProperty(prefix = "crag.event.consumer", name = "enabled", havingValue = "true")
  @ConditionalOnBean({JdbcProcessedEventDao.class, RedisStreamOps.class, DeadLetterPublisher.class})
  ThreadPoolTaskScheduler eventConsumerScheduler(
      JdbcProcessedEventDao processedDao,
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      DeadLetterPublisher dlqPublisher,
      ObjectProvider<EventHandler> handlerProvider,
      EventProperties properties) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("crag-event-con-");
    scheduler.initialize();
    scheduler.scheduleAtFixedRate(
        () -> pollOnce(processedDao, ops, mapper, dlqPublisher, handlerProvider, properties),
        properties.getPollInterval());
    log.info(
        "Event consumer scheduler started for stream {} group {} consumer {} (poll {})",
        properties.getStreamKey(),
        properties.getGroupName(),
        properties.getConsumerName(),
        properties.getPollInterval());
    return scheduler;
  }

  private void pollOnce(
      JdbcProcessedEventDao processedDao,
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      DeadLetterPublisher dlqPublisher,
      ObjectProvider<EventHandler> handlerProvider,
      EventProperties properties) {
    EventHandler handler = handlerProvider.getIfAvailable();
    if (handler == null) {
      log.info("Event consumer is enabled but no EventHandler bean is registered; not polling");
      return;
    }
    RedisStreamEventConsumer consumer =
        new RedisStreamEventConsumer(
            ops,
            mapper,
            processedDao,
            dlqPublisher,
            handler,
            properties.getStreamKey(),
            properties.getGroupName(),
            properties.getConsumerName(),
            properties.getBatchSize(),
            Clock.systemUTC());
    consumer.processNextBatch();
    RedisPendingReclaimer reclaimer =
        new RedisPendingReclaimer(
            ops,
            mapper,
            processedDao,
            dlqPublisher,
            consumer,
            properties.getStreamKey(),
            properties.getGroupName(),
            properties.getConsumerName(),
            properties.getClaimIdle(),
            properties.getMaxDeliveries(),
            properties.getBatchSize(),
            Clock.systemUTC());
    reclaimer.reclaimPending();
  }

  @Bean
  @ConditionalOnProperty(prefix = "crag.event.health", name = "enabled", havingValue = "true")
  @ConditionalOnBean({JdbcTemplate.class, StringRedisTemplate.class})
  @ConditionalOnClass(HealthIndicator.class)
  HealthIndicator eventHealthIndicator(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
    return new EventHealthIndicator(jdbcTemplate, redis);
  }
}
