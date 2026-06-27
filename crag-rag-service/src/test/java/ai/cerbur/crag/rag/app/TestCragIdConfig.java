package ai.cerbur.crag.rag.app;

import ai.cerbur.crag.id.api.CragIdGenerator;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only configuration that provides a mock {@link CragIdGenerator} bean.
 *
 * <p>The production {@code CragIdConfiguration} is gated by {@code crag.id.service-domain} and
 * requires Redis. Component tests that load the full {@link RagServiceApplication} context use this
 * in-memory generator so they can start without Redis.
 */
@TestConfiguration
class TestCragIdConfig {

  private final AtomicLong nextDocId = new AtomicLong(10000L);
  private final AtomicLong nextChunkId = new AtomicLong(20000L);

  @Bean
  CragIdGenerator cragIdGenerator() {
    return entityType ->
        switch (entityType) {
          case LEGACY_DOCUMENT -> nextDocId.getAndIncrement();
          case CHUNK -> nextChunkId.getAndIncrement();
          // Access entity types are not used by RAG tests; fall through to a shared counter.
          default -> nextDocId.getAndIncrement();
        };
  }
}
