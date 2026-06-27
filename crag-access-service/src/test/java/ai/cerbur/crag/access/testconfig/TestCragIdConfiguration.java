package ai.cerbur.crag.access.testconfig;

import ai.cerbur.crag.id.api.CragIdGenerator;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 测试专用 {@link CragIdGenerator}：内存自增计数器，避免组件测试依赖 Redis Worker 租约。
 *
 * <p>位于 {@code ai.cerbur.crag.access.testconfig}（{@code @ComponentScan("ai.cerbur.crag.access")}
 * 覆盖），因此所有 {@code @SpringBootTest} 上下文都能装配 {@link
 * ai.cerbur.crag.access.core.identity.IdentityService} 等依赖发号的 Bean。 仅存在于测试源，不进入主 jar。起始值远离
 * AccessDaoComponentTest 的硬编码小 ID，杜绝冲突。
 */
@Configuration
public class TestCragIdConfiguration {

  @Bean
  CragIdGenerator cragIdGenerator() {
    AtomicLong counter = new AtomicLong(1_000_000L);
    return entityType -> counter.getAndIncrement();
  }
}
