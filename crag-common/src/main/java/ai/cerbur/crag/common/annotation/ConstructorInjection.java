package ai.cerbur.crag.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记允许使用构造器注入的 Spring 组件, 作为 {@code @Autowired} 字段注入默认约定的例外.
 *
 * <p>仅当满足 {@code constraints/code-style.md} 依赖注入例外条件时才可使用: 框架、配置工厂、不可变值对象、 必须脱离 Spring 手工构造的对象,
 * 或存在明确测试与设计收益.
 *
 * <p>未标注此注解的 {@code @Service} 与 {@code @RestController} 由 ArchUnit 规则 7 检查, 禁止声明带参数构造器.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConstructorInjection {}
