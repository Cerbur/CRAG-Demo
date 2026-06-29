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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
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
import org.springframework.context.SmartLifecycle;
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
 *
 * <p>This configuration is ordered after the framework infra auto-configurations that create the
 * beans its {@code @ConditionalOnBean} members depend on ({@code DataSource}, {@code JdbcTemplate},
 * {@code StringRedisTemplate}, {@code MeterRegistry}). Without that ordering the conditions
 * evaluate before those beans are registered, so every event bean is skipped and the closed loop
 * silently never runs. FQN strings are used (not class refs) so this library does not
 * compile-couple to the framework autoconfigure packages and survives their repackaging.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
    })
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

  @Bean(name = "eventPublisherScheduler", destroyMethod = "stop")
  @ConditionalOnProperty(prefix = "crag.event.publisher", name = "enabled", havingValue = "true")
  @ConditionalOnBean({JdbcOutboxEventDao.class, RedisStreamOps.class})
  SmartLifecycle eventPublisherScheduler(
      JdbcOutboxEventDao outboxDao,
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      EventProperties properties) {
    return new EventSchedulerLifecycle("crag-event-pub-") {
      @Override
      ScheduledFuture<?> onStart(ThreadPoolTaskScheduler scheduler) {
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
        log.info(
            "Event publisher scheduler started for stream {} (poll {})",
            properties.getStreamKey(),
            properties.getPollInterval());
        return scheduler.scheduleAtFixedRate(
            () -> {
              for (OutboxClaim claim :
                  service.claimBatch(PUBLISHER_NAME, properties.getBatchSize())) {
                service.publish(claim, action);
              }
            },
            properties.getPollInterval());
      }
    };
  }

  /**
   * 上下文刷新完成后（{@link SmartLifecycle#start()}）再解析全部 {@link EventHandler} 并启动周期消费。
   *
   * <p>消费调度延后到 {@code finishRefresh}：刷新期间在 bean 方法内解析 handler 会经 handler 依赖链（例如 Knowledge {@code
   * IngestionStatusEventHandler} → {@code IngestionApplyService} → {@code DocumentRepository} → JPA
   * EntityManager）与 scheduler 形成 bean 循环依赖；调度线程在刷新未完成时解析又会触发 {@code @ConfigurationProperties} 绑定的
   * "has not been refreshed yet" 并发竞态。两个事件 scheduler 都作为 {@link EventSchedulerLifecycle} 拥有
   * <em>内部</em> {@link ThreadPoolTaskScheduler}（非 Spring Bean），不污染应用级 {@link
   * org.springframework.scheduling.TaskScheduler} 命名空间，Spring Boot 默认 {@code taskScheduler} 仍可用于
   * 应用调度（例如 Knowledge Ingestion Reconciler）。
   */
  @Bean(name = "eventConsumerScheduler", destroyMethod = "stop")
  @ConditionalOnProperty(prefix = "crag.event.consumer", name = "enabled", havingValue = "true")
  @ConditionalOnBean({JdbcProcessedEventDao.class, RedisStreamOps.class, DeadLetterPublisher.class})
  SmartLifecycle eventConsumerScheduler(
      JdbcProcessedEventDao processedDao,
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      DeadLetterPublisher dlqPublisher,
      ObjectProvider<EventHandler> handlerProvider,
      EventProperties properties) {
    return new EventSchedulerLifecycle("crag-event-con-") {
      @Override
      ScheduledFuture<?> onStart(ThreadPoolTaskScheduler scheduler) {
        List<EventHandler> handlers = handlerProvider.orderedStream().toList();
        for (EventHandler handler : handlers) {
          log.info(
              "Event consumer handler registered — stream {} group {} consumer {} types {}",
              handler.streamKey(),
              handler.groupName(),
              handler.consumerName(),
              handler.eventTypes());
        }
        if (handlers.isEmpty()) {
          log.info("Event consumer is enabled but no EventHandler bean is registered; not polling");
        }
        log.info(
            "Event consumer scheduler started ({} handler(s), poll {})",
            handlers.size(),
            properties.getPollInterval());
        return scheduler.scheduleAtFixedRate(
            () -> pollAllHandlers(handlers, processedDao, ops, mapper, dlqPublisher, properties),
            properties.getPollInterval());
      }
    };
  }

  /**
   * 事件 publisher/consumer 共用的 {@link SmartLifecycle}：上下文刷新完成后创建并初始化内部单线程 {@link
   * ThreadPoolTaskScheduler}，交由 {@link #onStart(ThreadPoolTaskScheduler)} 注册周期任务；停止时取消任务并关闭调度器。
   * 内部调度器不是 Spring Bean，避免与应用级 {@link org.springframework.scheduling.TaskScheduler} 注入冲突或抑制 Spring
   * Boot 默认 {@code taskScheduler}。
   */
  abstract static class EventSchedulerLifecycle implements SmartLifecycle {
    private final String threadNamePrefix;
    private volatile boolean running;
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;

    EventSchedulerLifecycle(String threadNamePrefix) {
      this.threadNamePrefix = threadNamePrefix;
    }

    /** 调度器初始化后调用一次；返回周期任务句柄（{@code null} 表示不调度）。 */
    abstract ScheduledFuture<?> onStart(ThreadPoolTaskScheduler scheduler);

    @Override
    public final synchronized void start() {
      if (running) {
        return;
      }
      scheduler = new ThreadPoolTaskScheduler();
      scheduler.setPoolSize(1);
      scheduler.setThreadNamePrefix(threadNamePrefix);
      scheduler.initialize();
      future = onStart(scheduler);
      running = true;
    }

    @Override
    public final synchronized void stop() {
      running = false;
      if (future != null) {
        future.cancel(false);
      }
      if (scheduler != null) {
        scheduler.shutdown();
      }
    }

    @Override
    public final boolean isRunning() {
      return running;
    }
  }

  /**
   * 为每个已注册的 {@link EventHandler} 在其各自 {@link EventHandler#streamKey()} / {@link
   * EventHandler#groupName()} / {@link EventHandler#consumerName()} 上独立消费一批并回收 pending。
   *
   * <p>正式事件拓扑要求同一服务可注册多个 handler（例如 Knowledge 同时消费 {@code INGESTION_*} 与 smoke 事件，各自 在不同 Redis
   * stream/group），因此消费调度按 handler 而非全局 {@link EventProperties} 单一 stream/group 驱动。 Open 的 {@code
   * EphemeralRedisStreamConsumer} 无 DB，其调度不在本方法范围。
   *
   * <p>{@code handlers} 由 {@code eventConsumerPoller} 在上下文刷新完成后一次性解析并捕获，本方法不在调度线程触发 bean 解析。
   */
  static void pollAllHandlers(
      Collection<EventHandler> handlers,
      JdbcProcessedEventDao processedDao,
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      DeadLetterPublisher dlqPublisher,
      EventProperties properties) {
    for (EventHandler handler : handlers) {
      RedisStreamEventConsumer consumer =
          new RedisStreamEventConsumer(
              ops,
              mapper,
              processedDao,
              dlqPublisher,
              handler,
              handler.streamKey(),
              handler.groupName(),
              handler.consumerName(),
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
              handler.streamKey(),
              handler.groupName(),
              handler.consumerName(),
              properties.getClaimIdle(),
              properties.getMaxDeliveries(),
              properties.getBatchSize(),
              Clock.systemUTC());
      reclaimer.reclaimPending();
    }
  }

  @Bean
  @ConditionalOnProperty(prefix = "crag.event.health", name = "enabled", havingValue = "true")
  @ConditionalOnBean({JdbcTemplate.class, StringRedisTemplate.class})
  @ConditionalOnClass(HealthIndicator.class)
  HealthIndicator eventHealthIndicator(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
    return new EventHealthIndicator(jdbcTemplate, redis);
  }
}
