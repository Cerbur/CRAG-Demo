package ai.cerbur.crag.rag.app.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * RAG 模块依赖架构测试（Plan 19）—— 静态结构校验 RAG 跨服务依赖只走 Knowledge contracts，不触及 Knowledge service 实现.
 *
 * <p>Gradle project 依赖白名单由 {@code scripts/validate_module_dependencies.py} 校验；这里用 ArchUnit 在 Java
 * 字节码层保证 RAG 不会误引用 {@code crag-knowledge-service} 的实现包，只允许 {@code
 * ai.cerbur.crag.contracts.knowledge.v1} 契约.
 */
@AnalyzeClasses(
    packages = "ai.cerbur.crag",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleDependencyArchitectureTest {

  @ArchTest
  static final ArchRule rag_does_not_access_knowledge_service =
      noClasses()
          .that()
          .resideInAPackage("ai.cerbur.crag..")
          .should()
          .accessClassesThat()
          .resideInAPackage("ai.cerbur.crag.knowledge..")
          .because(
              "RAG 只能依赖 crag-knowledge-contracts (ai.cerbur.crag.contracts.knowledge.v1)，"
                  + "不得依赖 crag-knowledge-service 的实现包。");
}
