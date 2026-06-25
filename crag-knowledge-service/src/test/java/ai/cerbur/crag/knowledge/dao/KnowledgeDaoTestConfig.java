package ai.cerbur.crag.knowledge.dao;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Knowledge DAO 测试专用窄上下文：仅装配 JPA + DAO，不加载 gRPC Server。
 *
 * <p>避免与其它 {@code @SpringBootTest} 共享全量应用上下文时触发 gRPC Server 单次使用的 "Already started" 重启问题；DAO 行为不需要
 * gRPC。配置类位于 {@code dao} 包，JPA 自动从该包扫描 entity 与 repository。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("ai.cerbur.crag.knowledge.dao")
class KnowledgeDaoTestConfig {}
