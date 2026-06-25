package ai.cerbur.crag.knowledge.smoke;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal web + JDBC + Jackson context for Knowledge smoke component tests, scoped to the smoke
 * package so gRPC, Platform Probe and schema-readiness beans are not loaded. Real
 * publisher/consumer scheduling is added by {@code crag-event} auto-configuration in a later task;
 * this config only exercises the smoke HTTP/service/handler path over H2.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "ai.cerbur.crag.knowledge.smoke")
public class KnowledgeSmokeTestConfig {}
