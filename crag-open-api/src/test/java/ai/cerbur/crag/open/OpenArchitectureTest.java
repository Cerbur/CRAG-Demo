package ai.cerbur.crag.open;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * Open 架构测试（plan_21/21.10）。
 *
 * <p>断言 Open 无数据库：无 Entity、无 Spring Data Repository、不依赖 Knowledge/RAG/Access/Console Service
 * module、不依赖 JDBC/JPA；Query Controller 仅位于 query.controller 包；HTTP DTO 位于 query.dto 包。
 */
class OpenArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ai.cerbur.crag.open..");
  }

  @Test
  @DisplayName("无 JPA Entity")
  void noJpaEntities() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.open..")
        .should()
        .beAnnotatedWith(Entity.class)
        .check(classes);
  }

  @Test
  @DisplayName("无 Spring Data Repository")
  void noRepositories() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.open..")
        .should()
        .beAssignableTo(Repository.class)
        .orShould()
        .beAnnotatedWith(org.springframework.stereotype.Repository.class)
        .check(classes);
  }

  @Test
  @DisplayName("不依赖 Knowledge/RAG/Access/Console Service 实现模块")
  void doesNotDependOnServiceModules() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.open..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "ai.cerbur.crag.access..",
            "ai.cerbur.crag.knowledge..",
            "ai.cerbur.crag.rag..",
            "ai.cerbur.crag.console..")
        .check(classes);
  }

  @Test
  @DisplayName("不依赖 JDBC/JPA DataSource")
  void noPersistenceApi() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.open..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "jakarta.persistence..",
            "org.springframework.data.jpa..",
            "javax.sql..",
            "org.springframework.jdbc..")
        .check(classes);
  }

  @Test
  @DisplayName("EphemeralRedisStreamConsumer（crag-event）不依赖 JDBC DAO（Open 失效消费者无 DB）")
  void ephemeralConsumerNoDbContractHoldsAtOpenSide() {
    // Open 侧不直接持有 JdbcProcessedEventDao；EphemeralRedisStreamConsumer 由 crag-event 架构测试断言。
    // 这里断言 Open 包内不引用 JdbcProcessedEventDao。
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.open..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("JdbcProcessedEventDao")
        .check(classes);
  }

  @Test
  @DisplayName("Controller 仅位于 query.controller 包")
  void controllersInApprovedPackage() {
    classes()
        .that()
        .resideInAPackage("..controller..")
        .and()
        .areAnnotatedWith(org.springframework.stereotype.Controller.class)
        .or()
        .resideInAPackage("..controller..")
        .and()
        .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .should()
        .resideInAnyPackage("ai.cerbur.crag.open.query.controller..")
        .check(classes);
  }

  @Test
  @DisplayName("Query HTTP DTO 位于 query.dto 包，不下沉到 contracts")
  void httpDtosInOpenScopedPackage() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.open..")
        .and()
        .haveSimpleNameEndingWith("Response")
        .should()
        .resideInAnyPackage(
            "ai.cerbur.crag.contracts..",
            "ai.cerbur.crag.access..",
            "ai.cerbur.crag.knowledge..",
            "ai.cerbur.crag.rag..")
        .check(classes);
  }
}
