package ai.cerbur.crag.knowledge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import ai.cerbur.crag.common.annotation.ConstructorInjection;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge 服务包结构与依赖护栏。
 *
 * <p>规则随 plan_18 各任务落地逐步激活：18.1 建立模块边界与注入约束；18.2 起新增 core/dao/grpc/
 * controller.smoke/producer/filestore 时，对应隔离规则对实际代码生效。指向尚未存在包的规则使用 {@code
 * allowEmptyShould(true)}，避免在增量实现期把“尚未实现”误判为违规。
 */
@AnalyzeClasses(
    packages = "ai.cerbur.crag",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class KnowledgeArchitectureTest {

  @ArchTest
  static final ArchRule no_module_cycles =
      slices().matching("ai.cerbur.crag.(*)..").should().beFreeOfCycles();

  /** Knowledge Repository 仅允许 DAO 包内部访问。 */
  @ArchTest
  static final ArchRule repository_cohesion =
      noClasses()
          .that()
          .resideOutsideOfPackage("ai.cerbur.crag.knowledge.dao..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage("ai.cerbur.crag.knowledge.dao.repository..")
          .allowEmptyShould(true)
          .because("Knowledge Repository 仅允许 DAO 包内部访问。");

  /** DAO 只管理数据库访问，不反向依赖 core/grpc/controller/producer/filestore。 */
  @ArchTest
  static final ArchRule dao_isolation =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag.knowledge.dao..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.knowledge.core..",
              "ai.cerbur.crag.knowledge.grpc..",
              "ai.cerbur.crag.knowledge.controller..",
              "ai.cerbur.crag.knowledge.producer..",
              "ai.cerbur.crag.knowledge.filestore..")
          .allowEmptyShould(true)
          .because("DAO 只管理数据库访问，不反向依赖上层业务或入口包。");

  /** core/grpc/controller/producer 不得直接依赖 Repository，必须经 DAO。 */
  @ArchTest
  static final ArchRule only_dao_uses_repository =
      noClasses()
          .that()
          .resideInAnyPackage(
              "ai.cerbur.crag.knowledge.core..",
              "ai.cerbur.crag.knowledge.grpc..",
              "ai.cerbur.crag.knowledge.controller..",
              "ai.cerbur.crag.knowledge.producer..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage("ai.cerbur.crag.knowledge.dao.repository..")
          .allowEmptyShould(true)
          .because("core/grpc/controller/producer 禁止直接依赖 Repository，必须经 DAO。");

  /** DAO 禁止新增 result 包，跨层结构由外部 mapper/converter 处理。 */
  @ArchTest
  static final ArchRule no_dao_result_package =
      noClasses()
          .should()
          .resideInAPackage("ai.cerbur.crag.knowledge.dao.result..")
          .because("DAO 不新增 result 包，跨层结构由外部 mapper/converter 处理。");

  /** Knowledge HTTP 入口统一收口到 smoke 验证包。 */
  @ArchTest
  static final ArchRule controller_location =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAnyPackage(
              "ai.cerbur.crag.knowledge.smoke.controller..",
              "ai.cerbur.crag.knowledge.controller.smoke..")
          .because("Knowledge HTTP 入口统一收口到 smoke 验证包。");

  /** Knowledge HTTP 入口必须受 @Profile(\"smoke\") 限制，默认启动不暴露。 */
  @ArchTest
  static final ArchRule smoke_controllers_have_profile =
      classes()
          .that()
          .resideInAPackage("ai.cerbur.crag.knowledge..")
          .and()
          .areAnnotatedWith(RestController.class)
          .should()
          .beAnnotatedWith(org.springframework.context.annotation.Profile.class)
          .because("Knowledge HTTP 入口必须受 @Profile(\"smoke\") 限制，默认启动不暴露。");

  /** 项目默认使用 @Autowired 字段注入，@Service/@RestController 不应声明依赖构造器。 */
  @ArchTest
  static final ArchRule spring_components_use_field_injection =
      classes()
          .that()
          .areAnnotatedWith(Service.class)
          .or()
          .areAnnotatedWith(RestController.class)
          .and()
          .areNotAnnotatedWith(ConstructorInjection.class)
          .should(
              new ArchCondition<>("不声明带参数构造器") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                  javaClass.getConstructors().stream()
                      .filter(constructor -> !constructor.getRawParameterTypes().isEmpty())
                      .forEach(
                          constructor ->
                              events.add(
                                  SimpleConditionEvent.violated(
                                      constructor,
                                      javaClass.getName() + " 应默认使用 @Autowired 字段注入，不应声明依赖构造器。")));
                }
              })
          .because("项目默认使用 @Autowired 字段注入。");

  /** Knowledge 不得依赖 RAG 业务模块。 */
  @ArchTest
  static final ArchRule knowledge_no_rag_business =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag.knowledge..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.storage..",
              "ai.cerbur.crag.ingestion..",
              "ai.cerbur.crag.retrieval..",
              "ai.cerbur.crag.query..")
          .allowEmptyShould(true)
          .because("Knowledge 不得依赖 RAG 业务模块。");
}
