package ai.cerbur.crag.open.config;

import ai.cerbur.crag.event.redis.DeadLetterPublisher;
import ai.cerbur.crag.event.redis.EphemeralRedisStreamConsumer;
import ai.cerbur.crag.event.redis.RedisStreamEventMapper;
import ai.cerbur.crag.event.redis.RedisStreamOps;
import ai.cerbur.crag.open.authcache.ApiKeyAuthCache;
import ai.cerbur.crag.open.authcache.OpenAuthCacheProperties;
import ai.cerbur.crag.open.consumer.ApiKeyInvalidationEventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Open 鉴权缓存与失效消费者 Bean 装配（plan_21/21.10）。
 *
 * <p>注册：
 *
 * <ul>
 *   <li>{@link ApiKeyAuthCache}（TTL/容量可配置，Metrics 接入 Micrometer）；
 *   <li>{@code EphemeralRedisStreamConsumer} 调度器（天然幂等模式，无 JDBC processed_event；仅在 Redis 可用且
 *       consumer 显式启用时启动）。
 * </ul>
 *
 * <p>Redis 不可用时 consumer 不启动；缓存与 Access 在线鉴权仍可工作（本地内存缓存无 Redis 依赖）。
 */
@Configuration
@EnableConfigurationProperties(OpenAuthCacheProperties.class)
public class OpenAuthCacheConfiguration {

  private static final Logger log = LoggerFactory.getLogger(OpenAuthCacheConfiguration.class);

  /**
   * 缓存 Bean，Metrics 接入 Micrometer（可选；未注册时用 Noop）。
   *
   * <p>计数器：{@code crag.open.apikey.cache.hits/misses/evictions/stale_rejections}。
   */
  @Bean
  public ApiKeyAuthCache apiKeyAuthCache(
      OpenAuthCacheProperties properties,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    ApiKeyAuthCache.Metrics metrics =
        meterRegistry == null ? new NoopMetrics() : new MicrometerMetrics(meterRegistry);
    return new ApiKeyAuthCache(
        properties.getTtl(), properties.getCapacity(), metrics, Clock.systemUTC());
  }

  /**
   * Ephemeral 消费调度器。仅当 Redis Stream ops 可用、{@link ApiKeyInvalidationEventHandler} 注册且 {@code
   * crag.event.consumer.enabled=true} 时启动。
   *
   * <p>使用 {@link EphemeralRedisStreamConsumer}（无 DB 幂等门），handler 天然幂等。
   */
  @Bean(name = "openInvalidationScheduler", destroyMethod = "shutdown")
  @ConditionalOnBean({
    RedisStreamOps.class,
    DeadLetterPublisher.class,
    ApiKeyInvalidationEventHandler.class
  })
  @ConditionalOnProperty(prefix = "crag.event.consumer", name = "enabled", havingValue = "true")
  @ConditionalOnClass(ThreadPoolTaskScheduler.class)
  public ThreadPoolTaskScheduler openInvalidationScheduler(
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      DeadLetterPublisher dlqPublisher,
      ApiKeyInvalidationEventHandler handler,
      OpenAuthCacheProperties properties) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("open-invalidation-");
    scheduler.initialize();
    EphemeralRedisStreamConsumer consumer =
        new EphemeralRedisStreamConsumer(
            ops,
            mapper,
            dlqPublisher,
            handler,
            handler.streamKey(),
            handler.groupName(),
            handler.consumerName(),
            20);
    scheduler.scheduleAtFixedRate(consumer::processNextBatch, java.time.Duration.ofSeconds(1));
    log.info(
        "Open invalidation consumer started for stream {} group {} consumer {} (ephemeral, no DB)",
        handler.streamKey(),
        handler.groupName(),
        handler.consumerName());
    return scheduler;
  }

  /** Noop Metrics，Micrometer 未注册时使用。 */
  private static final class NoopMetrics implements ApiKeyAuthCache.Metrics {
    @Override
    public void recordHit() {}

    @Override
    public void recordMiss() {}

    @Override
    public void recordEviction() {}

    @Override
    public void recordStaleRejection() {}
  }

  /** Micrometer-backed Metrics。 */
  private static final class MicrometerMetrics implements ApiKeyAuthCache.Metrics {
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong staleRejections = new AtomicLong();

    MicrometerMetrics(MeterRegistry registry) {
      registry.gauge("crag.open.apikey.cache.hits", hits);
      registry.gauge("crag.open.apikey.cache.misses", misses);
      registry.gauge("crag.open.apikey.cache.evictions", evictions);
      registry.gauge("crag.open.apikey.cache.stale_rejections", staleRejections);
    }

    @Override
    public void recordHit() {
      hits.incrementAndGet();
    }

    @Override
    public void recordMiss() {
      misses.incrementAndGet();
    }

    @Override
    public void recordEviction() {
      evictions.incrementAndGet();
    }

    @Override
    public void recordStaleRejection() {
      staleRejections.incrementAndGet();
    }
  }
}
