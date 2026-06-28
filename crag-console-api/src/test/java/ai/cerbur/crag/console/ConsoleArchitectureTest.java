package ai.cerbur.crag.console;

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
 * Console 架构测试（plan_21/21.6）。
 *
 * <p>断言 Console 无数据库：无 Entity、无 Spring Data Repository、无 DAO；不依赖 Access/Knowledge/RAG Service
 * module；正式 Controller 不在 smoke 包；RefreshCookieService 等安全适配器位于 security/auth 包。
 */
class ConsoleArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ai.cerbur.crag.console..");
  }

  @Test
  @DisplayName("无 JPA Entity")
  void noJpaEntities() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.console..")
        .should()
        .beAnnotatedWith(Entity.class)
        .check(classes);
  }

  @Test
  @DisplayName("无 Spring Data Repository")
  void noRepositories() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.console..")
        .should()
        .beAssignableTo(Repository.class)
        .orShould()
        .beAnnotatedWith(org.springframework.stereotype.Repository.class)
        .check(classes);
  }

  @Test
  @DisplayName("不依赖 Access/Knowledge/RAG Service 实现模块")
  void doesNotDependOnServiceModules() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.console..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "ai.cerbur.crag.access..", "ai.cerbur.crag.knowledge..", "ai.cerbur.crag.rag..")
        .check(classes);
  }

  @Test
  @DisplayName("不依赖 JDBC/JPA DataSource")
  void noPersistenceApi() {
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.console..")
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
  @DisplayName("Controller 仅存在于 auth/controller 包")
  void controllersInAuthPackage() {
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
        .resideInAPackage("ai.cerbur.crag.console.auth.controller..")
        .check(classes);
  }
}
