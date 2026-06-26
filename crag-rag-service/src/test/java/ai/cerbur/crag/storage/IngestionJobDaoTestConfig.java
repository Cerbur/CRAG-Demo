package ai.cerbur.crag.storage;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * IngestionJob DAO 测试专用窄上下文（Plan 19）：仅装配 JPA + storage DAO，不加载 gRPC Server、检索或 LLM.
 *
 * <p>配置类位于 {@code ai.cerbur.crag.storage} 包，JPA 从该包扫描 entity 与 repository。避免全量应用上下文带来的 gRPC/LLM
 * 噪音。H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("ai.cerbur.crag.storage")
class IngestionJobDaoTestConfig {}
