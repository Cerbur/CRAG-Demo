package ai.cerbur.crag.smoke;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 测试专用最小 Spring Boot 配置 —— 为 crag-api 库模块的 Spring Boot 测试提供配置.
 *
 * <p>仅存在于 test source set。不排除任何自动配置；WebMvcTest 切片自动限制加载范围。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestSpringBootConfiguration {}
