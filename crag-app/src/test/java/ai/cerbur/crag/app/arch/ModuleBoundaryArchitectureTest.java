package ai.cerbur.crag.app.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块边界架构测试基线.
 *
 * <p>验证包边界、模块依赖和公开 API 规则。当前版本的临时例外由 {@code freeze} 机制记录，
 * 由 plan_9 任务 9.2～9.5 逐步消除。所有冻结例外可查看 {@code archunit_store/} 目录。
 *
 * <p>新增同类越界类会导致测试失败；已记录的例外在对应任务完成后必须删除冻结记录。
 */
@AnalyzeClasses(
    packages = "ai.cerbur.crag",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryArchitectureTest {

  // ═══════════════════════════════════════════════════════════════
  // 规则 1：代码依赖无环
  // ═══════════════════════════════════════════════════════════════

  /**
   * 所有顶层模块包之间不得形成依赖环。当前无违反。
   */
  @ArchTest
  static final ArchRule no_module_cycles =
      slices()
          .matching("ai.cerbur.crag.(*)..")
          .should()
          .beFreeOfCycles();

  // ═══════════════════════════════════════════════════════════════
  // 规则 2：Repository 内聚
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-storage} 的 {@code repository} 包只能被 Storage 模块内部访问。当前无违反。
   */
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
   * {@code @RestController} 只允许出现在 {@code crag-admin.controller} 包（将重命名为
   * {@code crag-api}）。
   *
   * <p>冻结例外（由任务 9.5 删除）：{@code TestController} 当前位于 {@code
   * ai.cerbur.crag.app.controller}。
   */
  @ArchTest
  static final ArchRule controller_location =
      freeze(
          classes()
              .that()
              .areAnnotatedWith(RestController.class)
              .should()
              .resideInAnyPackage("ai.cerbur.crag.admin.controller..")
              .because(
                  "@RestController 仅允许在 crag-admin（将重命名为 crag-api）和 crag-smoke（待 9.5"
                      + " 创建）中。冻结例外：TestController — 由 9.5 删除。"));

  // ═══════════════════════════════════════════════════════════════
  // 规则 4：crag-app 禁止业务调用
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-app} 是唯一组合根，禁止在 Java 代码中直接调用任何业务模块。
   *
   * <p>冻结例外（由任务 9.5 删除）：{@code TestController} 直接调用 {@code ChunkDao}、{@code
   * DenseQueryService}、{@code RetrievalService} 等存储和检索内部组件。
   */
  @ArchTest
  static final ArchRule app_no_business_calls =
      freeze(
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
              .because(
                  "crag-app 是组合根，禁止直接调用业务模块的 Java 类型。"
                      + "冻结例外：TestController 对 DAO/Retrieval 的访问 — 由 9.5 删除。"));

  // ═══════════════════════════════════════════════════════════════
  // 规则 5a：crag-admin → crag-ingestion 仅允许 api 包
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-admin} 只能通过 {@code crag-ingestion} 的 {@code api} 包访问其公开入口。
   *
   * <p>冻结例外（由任务 9.3 修复）：{@code AdminRagController} 当前直接 import {@code
   * ingestion.service.AdminRagService} 和 {@code ingestion.service.AdminRagResult}。
   */
  @ArchTest
  static final ArchRule admin_only_ingestion_api =
      freeze(
          noClasses()
              .that()
              .resideInAPackage("ai.cerbur.crag.admin..")
              .should()
              .accessClassesThat()
              .resideInAnyPackage(
                  "ai.cerbur.crag.ingestion.service..",
                  "ai.cerbur.crag.ingestion.chunk..",
                  "ai.cerbur.crag.ingestion.dense..",
                  "ai.cerbur.crag.ingestion.cron..")
              .because(
                  "crag-admin 只能通过 ingestion.api 包访问。"
                      + "冻结例外：AdminRagController→ingestion.service — 由 9.3 修复。"));

  // ═══════════════════════════════════════════════════════════════
  // 规则 5b：crag-ingestion → crag-retrieval 仅允许 api 包
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-ingestion} 只能通过 {@code crag-retrieval} 的 {@code api} 包访问其公开入口。
   *
   * <p>冻结例外（由任务 9.4 修复）：{@code DenseEmbeddingService} 和 {@code DenseEmbeddingCron}
   * 当前直接 import {@code retrieval.embedding.EmbeddingClient} 和 {@code
   * retrieval.embedding.EmbeddingException}。
   */
  @ArchTest
  static final ArchRule ingestion_only_retrieval_api =
      freeze(
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
              .because(
                  "crag-ingestion 只能通过 retrieval.api 包访问。"
                      + "冻结例外：DenseEmbeddingService/Cron→retrieval.embedding — 由 9.4 修复。"));

  // ═══════════════════════════════════════════════════════════════
  // 规则 5c：crag-query → crag-retrieval 仅允许 api 包
  // ═══════════════════════════════════════════════════════════════

  /**
   * {@code crag-query} 只能通过 {@code crag-retrieval} 的 {@code api} 包访问其公开入口。当前无违反（{@code
   * UserQueryService} 尚未引用 Retrieval 类型）。
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



}
