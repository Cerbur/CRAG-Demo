package ai.cerbur.crag.app.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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
 * 模块边界架构测试基线.
 *
 * <p>验证包边界、模块依赖和公开 API 规则。plan_9 迁移期例外已全部消除（9.3/9.4/9.5）， 当前所有规则无豁免直接通过。
 *
 * <p>新增同类越界类会导致测试失败。
 */
@AnalyzeClasses(
    packages = "ai.cerbur.crag",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryArchitectureTest {

  // ═══════════════════════════════════════════════════════════════
  // 规则 1：代码依赖无环
  // ═══════════════════════════════════════════════════════════════

  /** 所有顶层模块包之间不得形成依赖环。当前无违反。 */
  @ArchTest
  static final ArchRule no_module_cycles =
      slices().matching("ai.cerbur.crag.(*)..").should().beFreeOfCycles();

  // ═══════════════════════════════════════════════════════════════
  // 规则 2：Repository 内聚
  // ═══════════════════════════════════════════════════════════════

  /** {@code crag-storage} 的 {@code repository} 包只能被 Storage 模块内部访问。当前无违反。 */
  @ArchTest
  static final ArchRule repository_cohesion =
      noClasses()
          .that()
          .resideOutsideOfPackage("ai.cerbur.crag.storage..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage("ai.cerbur.crag.storage.repository..")
          .because("Repository 仅允许 Storage 内部访问；当前无跨模块违反。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 3：Controller 位置
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code @RestController} 只允许出现在 {@code crag-api.controller} 和受控诊断例外 {@code
   * crag-smoke.controller} 包。
   *
   * <p>原冻结例外（TestController 位于 crag-app）已由 9.5 消除。
   */
  @ArchTest
  static final ArchRule controller_location =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAnyPackage(
              "ai.cerbur.crag.api.controller..", "ai.cerbur.crag.smoke.controller..")
          .because("@RestController 仅允许在 crag-api 和 crag-smoke（诊断例外）中。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 4：crag-app 禁止业务调用
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-app} 是唯一组合根，禁止在 Java 代码中直接调用任何业务模块。
   *
   * <p>原冻结例外（TestController 对 DAO/Retrieval 的访问）已由 9.5 消除。当前无违反。
   */
  @ArchTest
  static final ArchRule app_no_business_calls =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag.app..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.storage..",
              "ai.cerbur.crag.retrieval..",
              "ai.cerbur.crag.ingestion..",
              "ai.cerbur.crag.query..")
          .because("crag-app 是组合根，禁止直接调用业务模块的 Java 类型。TestController 已由 9.5 迁入 crag-smoke。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 5a：crag-api → crag-ingestion 仅允许 api 包
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-api} 只能通过 {@code crag-ingestion} 的 {@code api} 包访问其公开入口。
   *
   * <p>原冻结例外（AdminRagController→ingestion.service）已由 9.3 消除。当前无违反。
   */
  @ArchTest
  static final ArchRule admin_only_ingestion_api =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag.api..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "ai.cerbur.crag.ingestion.service..",
              "ai.cerbur.crag.ingestion.chunk..",
              "ai.cerbur.crag.ingestion.dense..",
              "ai.cerbur.crag.ingestion.cron..")
          .because("crag-api 只能通过 ingestion.api 包访问。原例外已由 9.3 消除。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 5b：crag-ingestion → crag-retrieval 仅允许 api 包
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-ingestion} 只能通过 {@code crag-retrieval} 的 {@code api} 包访问其公开入口。
   *
   * <p>原冻结例外（DenseEmbeddingService/Cron→retrieval.embedding）已由 9.4 消除。当前无违反。
   */
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
          .because("crag-ingestion 只能通过 retrieval.api 包访问。原例外已由 9.4 消除。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 5c：crag-query → crag-retrieval 仅允许 api 包
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-query} 只能通过 {@code crag-retrieval} 的 {@code api} 包访问其公开入口. {@code UserQueryService}
   * 通过允许的 {@code retrieval.api} 引用 {@code RetrievalService} 与 {@code ParentEvidenceResult}。
   */
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
          .because("crag-query 只能通过 retrieval.api 包访问。当前无违反。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 6：Smoke Bean 必须受 Profile 限制
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-smoke} 模块中标记了 {@code @RestController} 的类必须同时标记 {@code @Profile("smoke")}，
   * 确保默认应用启动不暴露诊断端点。
   *
   * <p>不禁止业务模块依赖 {@code crag-smoke} 由依赖白名单校验器覆盖。
   */
  @ArchTest
  static final ArchRule smoke_controllers_have_profile =
      classes()
          .that()
          .resideInAPackage("ai.cerbur.crag.smoke..")
          .and()
          .areAnnotatedWith(RestController.class)
          .should()
          .beAnnotatedWith(org.springframework.context.annotation.Profile.class)
          .because("crag-smoke 的诊断端点必须受 @Profile(\"smoke\") 限制。");

  // ═══════════════════════════════════════════════════════════════
  // 规则 7：Spring 组件默认使用字段注入
  // ═══════════════════════════════════════════════════════════════

  /** Service 与 RestController 不得声明带参数构造器，避免再次引入非必要构造器注入。 */
  @ArchTest
  static final ArchRule spring_components_use_field_injection =
      classes()
          .that()
          .areAnnotatedWith(Service.class)
          .or()
          .areAnnotatedWith(RestController.class)
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
                                      javaClass.getName() + " 应默认使用 @Autowired 字段注入，不应声明依赖构造器")));
                }
              })
          .because("项目默认使用 @Autowired 字段注入；构造器注入需要明确且必要的例外理由。");
}
