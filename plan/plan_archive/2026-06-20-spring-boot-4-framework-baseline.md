# 归档决策：Spring Boot 4.1.0 + Spring AI 2.0.0 框架基线升级

> 归档日期：2026-06-20
> 关联计划：plan_13

## Before

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.4.1 | 通过 `implementation(platform(...))` 在每个模块单独导入 BOM |
| Spring Framework | 6.x（由 Boot 3.4.1 BOM 管理） | 无显式声明 |
| Spring AI | 1.0.0-M5（仅 OpenAI Starter） | 通过 `spring-ai-openai-spring-boot-starter` M5 里程碑版本间接获得 `Document` / `TokenTextSplitter` |
| Jackson | 2.x（由 Boot 3 BOM 管理） | `com.fasterxml.jackson.databind.ObjectMapper` |
| 构建治理 | 分散：无 version catalog，子模块重复 `platform(...)`，含 Spring Milestone 仓库 | 无集中版本源、无自动防漂移门禁 |
| 自动配置 | 排除 `OpenAiAutoConfiguration` + dummy API key | plan_1/plan_2 工程期遗留 |

## After

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 4.1.0 | 通过 Gradle version catalog (`libs.versions.toml`) 集中声明，`io.spring.dependency-management` 插件在 root `subprojects` 中导入 Boot BOM |
| Spring Framework | 7.0.8（由 Boot 4.1.0 BOM 管理） | 不显式覆盖，禁止显式固定版本 |
| Spring AI | 2.0.0 | Spring AI BOM 仅在 `crag-ingestion` 子模块中独立导入（通过其自身 `build.gradle.kts` 的 `dependencyManagement` 块），`crag-ingestion` 仅依赖 `spring-ai-commons`；根构建不导入 Spring AI BOM |
| Jackson | 3.x（由 Boot 4 BOM 管理） | `tools.jackson.databind.ObjectMapper` / `tools.jackson.core.JacksonException`，注解保持 `com.fasterxml.jackson.annotation.*` |
| 构建治理 | 集中：catalog 为唯一版本源，root subprojects 统一 BOM，框架依赖校验器 + 根 `check` 接线 | 移除 Milestone 仓库、OpenAI Starter、dummy API key |
| 自动配置 | 无排除、无 dummy key | plan_7 接入 Provider 时再按需管理 |

## 影响范围

- **构建系统**：新增 `gradle/libs.versions.toml`；root `build.gradle.kts` 应用 `java-base` + `io.spring.dependency-management`；所有模块 build 文件移除 `platform()` 和硬编码版本；`settings.gradle.kts` 移除 Milestone 仓库。
- **生产代码**：
  - `AdminRagService` 改为构造器注入，新增 `MetadataSerializationException` 保留 Jackson cause（映射至既有 `50001`）。
  - `CragDemoApplication` 移除已删除的 `@EntityScan`，改由 `AutoConfigurationPackages` 程序化注册实体包。
  - Jackson 3 导入路径变更。
  - 移除 `application.yml` 中的 OpenAI 自动配置排除和 dummy API key。
- **测试**：
  - `@WebMvcTest` 导入路径更新为 Boot 4 新包。
  - `NoResourceFoundException` 构造器适配 Framework 7 新签名。
  - MVC 测试切片使用 `spring-boot-starter-webmvc-test`。
  - 移除已无用的 `TestSpringBootConfiguration` 后重建为最简形态。
- **验证**：新增 `scripts/validate_framework_dependencies.py` 及其单元测试，接入根 `check`。
- **Docker**：`crag-app` 固定 Boot Jar 输出为 `crag-demo.jar`，Dockerfile 精确复制该文件。
- **HTTP 回归**：修复 `smoke_default_test.sh` 连接失败假阳性、`retrieval_evidence_test.sh` 超时后继续的隔离漏洞，`smoke_endpoints_test.sh` 增加唯一 runId。

## 迁移要点

1. 先建立 version catalog 和根 dependency-management 导入，验证依赖解析。
2. 逐模块移除 `platform()`，替换 Boot 4 模块化 Starter（`webmvc` / `webmvc-test`）。
3. 迁移 Jackson 3 导入，修改 `AdminRagService` 和受影响的测试。
4. 修正 Spring Framework 7 破坏性变化（`NoResourceFoundException` 构造器、`@EntityScan` 移除）。
5. 固定 Boot Jar、修复 HTTP 回归假阳性。
6. 同步项目方向文档、约束和下游计划。

## 回滚

按 `13.3 → 13.1` 逆序撤销提交：

1. 恢复文档、计划索引和归档记录。
2. 恢复 `Dockerfile` 通配符复制和 plain Jar。
3. 恢复 Boot 3.4.1、旧 `platform()` 导入、OpenAI M5 Starter、Jackson 2 导入、旧 `@EntityScan`、旧 `@WebMvcTest` 包、application.yml 排除项和 dummy key。

数据库无 schema 迁移，回滚不删除测试数据。
