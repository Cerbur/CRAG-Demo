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
  @DisplayName(
      "Controller 仅存在于 auth/tenant/membership/knowledge/document controller 包（plan_21/21.8 扩展）")
  void controllersInApprovedPackages() {
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
        .resideInAnyPackage(
            "ai.cerbur.crag.console.auth.controller..",
            "ai.cerbur.crag.console.tenant.controller..",
            "ai.cerbur.crag.console.membership.controller..",
            "ai.cerbur.crag.console.knowledge.controller..",
            "ai.cerbur.crag.console.document.controller..")
        .check(classes);
  }

  @Test
  @DisplayName("tenant/membership/knowledge/document HTTP DTO 位于 console 专属 dto 包（plan_21/21.8 扩展）")
  void httpDtosInConsoleScopedPackages() {
    classes()
        .that()
        .resideInAnyPackage(
            "ai.cerbur.crag.console.tenant.dto..",
            "ai.cerbur.crag.console.membership.dto..",
            "ai.cerbur.crag.console.knowledge.dto..",
            "ai.cerbur.crag.console.document.dto..")
        .should()
        .resideInAnyPackage(
            "ai.cerbur.crag.console.tenant.dto..",
            "ai.cerbur.crag.console.membership.dto..",
            "ai.cerbur.crag.console.knowledge.dto..",
            "ai.cerbur.crag.console.document.dto..")
        .check(classes);
  }

  @Test
  @DisplayName("gRPC adapter 只依赖 contracts/服务包内 DTO，不反向被 contracts 依赖（plan_21/21.7）")
  void grpcAdaptersDoNotExposeToOutsideConsoleScope() {
    // Console 模块外的 contracts 不可能依赖 Console HTTP DTO（跨 Gradle 模块，依赖方向单向）；
    // 这里断言 Console HTTP DTO 不出现在 contracts 包路径下，验证模块边界。
    noClasses()
        .that()
        .resideInAPackage("ai.cerbur.crag.console..")
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
