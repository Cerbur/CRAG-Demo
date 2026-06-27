package ai.cerbur.crag.access;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Access 架构测试（ArchUnit）。
 *
 * <p>验证持久化边界：Repository 只能由 DAO 调用，Entity 仅存在于 {@code dao.entity}，DAO 不依赖 gRPC、Controller 或 Core
 * 业务包。 Security 适配器位于 {@code security} 包。这些规则为后续 20.3–20.8 Core/Provider 增长提供防漂移护栏。
 */
class AccessArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ai.cerbur.crag.access..");
  }

  @Test
  @DisplayName("Repository 只允许 DAO 包访问")
  void repositoryOnlyAccessedByDao() {
    classes()
        .that()
        .resideInAPackage("..dao.repository..")
        .should()
        .onlyBeAccessed()
        .byClassesThat()
        .resideInAPackage("..dao..")
        .check(classes);
  }

  @Test
  @DisplayName("Repository 依赖方只能是 DAO 包")
  void repositoryDependentsOnlyDao() {
    classes()
        .that()
        .resideInAPackage("..dao.repository..")
        .should()
        .onlyHaveDependentClassesThat()
        .resideInAnyPackage("..dao..")
        .check(classes);
  }

  @Test
  @DisplayName("JPA Entity 只能存在于 dao.entity 包")
  void entitiesResideInDaoEntity() {
    classes()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .resideInAPackage("..dao.entity..")
        .because("持久化 Entity 不得跨边界传播")
        .check(classes);
  }

  @Test
  @DisplayName("DAO 不依赖 gRPC、Controller 或 Producer")
  void daoNoProtocolDependencies() {
    classes()
        .that()
        .resideInAPackage("..dao..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "java..",
            "jakarta.persistence..",
            "org.springframework..",
            "ai.cerbur.crag.access.dao..")
        .check(classes);
  }

  @Test
  @DisplayName("Security 适配器位于 security 包")
  void securityAdaptersInSecurityPackage() {
    classes()
        .that()
        .implement(ai.cerbur.crag.access.security.PasswordHasher.class)
        .should()
        .resideInAPackage("..security..")
        .check(classes);
    classes()
        .that()
        .implement(ai.cerbur.crag.access.security.SecretHmac.class)
        .should()
        .resideInAPackage("..security..")
        .check(classes);
    classes()
        .that()
        .implement(ai.cerbur.crag.access.security.SecretGenerator.class)
        .should()
        .resideInAPackage("..security..")
        .check(classes);
  }
}
