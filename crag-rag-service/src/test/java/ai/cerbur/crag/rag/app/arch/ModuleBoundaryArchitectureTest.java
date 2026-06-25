package ai.cerbur.crag.rag.app.arch;

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

@AnalyzeClasses(
    packages = "ai.cerbur.crag",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryArchitectureTest {

  @ArchTest
  static final ArchRule no_module_cycles =
      slices().matching("ai.cerbur.crag.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule repository_cohesion =
      noClasses()
          .that()
          .resideOutsideOfPackage("ai.cerbur.crag.storage..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage("ai.cerbur.crag.storage.repository..")
          .because("Repository 仅允许 Storage 内部访问。");

  @ArchTest
  static final ArchRule controller_location =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAnyPackage("ai.cerbur.crag.smoke.controller..")
          .because("legacy RAG HTTP 验证端点统一收口到 smoke 验证 HTTP 包。");

  @ArchTest
  static final ArchRule app_no_business_calls =
      noClasses()
          .that()
          .resideInAnyPackage("ai.cerbur.crag.rag.app..", "ai.cerbur.crag.app..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.storage..",
              "ai.cerbur.crag.retrieval..",
              "ai.cerbur.crag.ingestion..",
              "ai.cerbur.crag.query..")
          .because("组合根禁止直接调用业务模块的 Java 类型。");

  @ArchTest
  static final ArchRule ingestion_only_retrieval_api =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag.ingestion..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.retrieval.embedding..",
              "ai.cerbur.crag.retrieval.service..",
              "ai.cerbur.crag.retrieval.dense..",
              "ai.cerbur.crag.retrieval.sparse..",
              "ai.cerbur.crag.retrieval.rrf..",
              "ai.cerbur.crag.retrieval.rerank..",
              "ai.cerbur.crag.retrieval.bo..",
              "ai.cerbur.crag.retrieval.result..")
          .because("crag-ingestion 只能通过 retrieval.api 包访问。");

  @ArchTest
  static final ArchRule query_only_retrieval_api =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag.query..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.retrieval.embedding..",
              "ai.cerbur.crag.retrieval.service..",
              "ai.cerbur.crag.retrieval.dense..",
              "ai.cerbur.crag.retrieval.sparse..",
              "ai.cerbur.crag.retrieval.rrf..",
              "ai.cerbur.crag.retrieval.rerank..",
              "ai.cerbur.crag.retrieval.bo..",
              "ai.cerbur.crag.retrieval.result..")
          .allowEmptyShould(true)
          .because("crag-query 只能通过 retrieval.api 包访问。");

  @ArchTest
  static final ArchRule smoke_controllers_have_profile =
      classes()
          .that()
          .resideInAPackage("ai.cerbur.crag.smoke..")
          .and()
          .areAnnotatedWith(RestController.class)
          .should()
          .beAnnotatedWith(org.springframework.context.annotation.Profile.class)
          .because("smoke 验证端点必须受 @Profile(\"smoke\") 限制，默认启动不暴露。");

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

  @ArchTest
  static final ArchRule access_knowledge_no_rag_business =
      noClasses()
          .that()
          .resideInAnyPackage("ai.cerbur.crag.access..", "ai.cerbur.crag.knowledge..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.storage..",
              "ai.cerbur.crag.ingestion..",
              "ai.cerbur.crag.retrieval..",
              "ai.cerbur.crag.query..",
              "ai.cerbur.crag.smoke..")
          .allowEmptyShould(true)
          .because("Access/Knowledge 不得依赖现有 RAG 业务模块与 smoke 验证 HTTP。");
}
