package ai.cerbur.crag.event.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ai.cerbur.crag.event.redis.EphemeralRedisStreamConsumer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * crag-event 架构测试（plan_21/21.10）。
 *
 * <p>断言 {@link EphemeralRedisStreamConsumer} 不依赖任何数据库 DAO（不注入 {@code JdbcProcessedEventDao}、不引用
 * {@code javax.sql} 或 JDBC）。这是 Open 失效消费"天然幂等 + 临时缓存、不引入 processed_event 数据库"设计的硬约束。
 */
class EphemeralConsumerNoDbArchitectureTest {

  private static com.tngtech.archunit.core.domain.JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ai.cerbur.crag.event..");
  }

  @Test
  @DisplayName("EphemeralRedisStreamConsumer 不依赖 JdbcProcessedEventDao")
  void ephemeralConsumerDoesNotDependOnProcessedEventDao() {
    noClasses()
        .that()
        .haveSimpleName("EphemeralRedisStreamConsumer")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("JdbcProcessedEventDao")
        .check(classes);
  }

  @Test
  @DisplayName("EphemeralRedisStreamConsumer 不依赖 javax.sql / JDBC / DataSource")
  void ephemeralConsumerDoesNotDependOnJdbc() {
    noClasses()
        .that()
        .haveSimpleName("EphemeralRedisStreamConsumer")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("javax.sql..", "java.sql..")
        .check(classes);
  }
}
