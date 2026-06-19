---
workflow_version: 2
plan_id: plan_12
type: main
status: completed
owner: parent-agent
created: 2026-06-19
updated: 2026-06-19
---

# plan_12 — 约束事实校准与防漂移护栏

## 背景与目标

`constraints/` 分层改造已经建立 Plan、Java、API、持久化、Retrieval、包结构、Docker 和测试八类入口，现有 `./gradlew check`、Plan 校验与模块依赖校验均能通过。但复核发现仍有三类治理缺口：

1. `constraints/docker-structure.md` 未记录 Compose 中已经存在的 `model-init`、`sidecar`、`app-smoke`、健康检查、模型缓存和服务依赖，当前事实明显漂移。
2. `AGENTS.md` 与 `CLAUDE.md` 是完全相同的约束路由入口，但没有机械一致性校验，后续可能产生双写分叉。
3. Storage API 被称为“迁移期例外”，却没有承诺迁移任务或期限；更准确的语义应是由架构测试锁定、仅在耦合恶化时重新决策的“受控架构例外”。

本计划只校准治理文档中的当前事实和术语，并新增小而确定的约束校验器。它不修改 Java、Compose、Dockerfile 或运行时行为。

## 范围

- 将 Docker 约束改造成区分“架构硬约束、当前实现索引、已知偏差”的准确入口。
- 当前实现索引从 `docker-compose.yml` 对齐 `db`、`model-init`、`sidecar`、`app`、`app-smoke` 五个服务。
- 将 Storage “迁移期例外”统一改称“受控架构例外”，保留现有白名单和禁止扩大 Entity 泄漏的边界。
- 新增 `scripts/validate_constraints.py` 与对应测试。
- 校验 `AGENTS.md` / `CLAUDE.md` 完全一致、现行约束路由链接有效、Compose 服务已登记、明确替代术语未回流。
- 将 `validateConstraints` 接入根 `./gradlew check`。
- 创建 `plan_9.hotfix_3`，并同步 `plan_7`、`plan_10` 与执行队列的依赖事实。

## 非目标

- 不修改 Java 源码、HTTP 契约、Gradle 模块依赖、Compose、Dockerfile、应用配置或运行时行为。
- 不提前实现 `plan_10` 的正式健康检查、镜像与部署对齐。
- 不自动判断“职责是否合理”等语义性规则。
- 不扫描或改写历史 Plan 中保留的旧术语与旧链接。
- 不将合法业务名 `AdminRag` 视为废弃术语。

## 前置依赖

- **执行前置 Plan**：无
- `plan_9`、`plan_11` 已完成，当前模块边界和测试分类可作为事实基线。
- 本计划不依赖 `plan_7` 或 `plan_10` 的未来实现。

## 文件边界

- `constraints/docker-structure.md`
- `constraints/package-structure.md`
- `constraints/persistence-style.md`
- `AGENTS.md`
- `CLAUDE.md`
- `docker-compose.yml`（只读事实来源）
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `build.gradle.kts`
- `plan/plan_12/plan_12.md`
- `plan/plan_9/plan_9.hotfix_3.md`
- `plan/plan_7/plan_7.md`
- `plan/plan_10/plan_10.md`
- `plan/index/README.md`

## 关键决策

- `plan_10` 保持原有目标与前置依赖，不拆分任务；本计划仅把当前部署事实写准并登记尚待 `plan_10` 修复的偏差。
- Docker 服务列表以 `docker-compose.yml` 为实现事实来源。校验器解析 Compose 顶层 `services` 键，并要求约束的当前实现索引逐项登记，不在脚本中维护第三份固定服务清单。
- 不引入 YAML 第三方依赖；校验器只解析本仓库 Compose 的受限顶层结构，遇到无法可靠解析的结构时明确失败。
- `AGENTS.md` 与 `CLAUDE.md` 继续作为两份兼容入口，不使用软链接；校验器要求字节内容完全一致。
- 现行链接校验只覆盖 `AGENTS.md`、`CLAUDE.md` 和 `constraints/*.md`，不追溯历史 Plan。
- 术语校验只维护明确替代关系，并支持文档中的显式允许上下文：
  - `crag-admin` 只允许出现在“禁止新增”的兼容说明中，当前模块事实统一使用 `crag-api`。
  - `迁移期例外` 统一替换为 `受控架构例外`。
  - 测试分类禁止重新使用宽泛的“非单元测试”，统一使用四层术语。
- Storage 例外继续由现有架构白名单约束；只有实际跨模块耦合恶化时才创建计划收口 Storage API，不保留虚假迁移承诺。
- 校验器只做确定性文本和结构检查，不替代 Review、ArchUnit、Plan 校验或模块依赖校验。

## 未决问题

无。校验范围、允许例外、脚本命名、Gradle 任务名和后续执行顺序均已在本轮 grilling 中确认。

## 风险与回滚

- 受限 Compose 解析可能在未来复杂 YAML 语法下误判：脚本必须对解析边界写测试，无法判断时失败并提示升级解析方式，禁止静默漏检。
- 术语扫描可能误伤合法历史说明：扫描范围排除历史 Plan，并使用按文件、按语境的明确允许规则。
- Docker 文档可能把未来目标误写为当前事实：文档必须分开“当前实现索引”和“已知偏差”，逐项对照 Compose、Dockerfile 与应用配置。
- 本计划无运行时修改。失败时可按任务逆序撤销文档、脚本和 Gradle 接入提交，不涉及数据或部署回滚。

## 测试与验证计划

- Python 纯单元测试：`python3 -m unittest scripts.tests.test_validate_constraints`，覆盖入口一致、链接、Compose 服务同步、允许术语和禁止术语。
- 独立约束校验：`python3 scripts/validate_constraints.py`。
- Plan 严格校验：`python3 scripts/validate_plans.py --strict`。
- 模块依赖校验：`python3 scripts/validate_module_dependencies.py`。
- Gradle 全量校验：`./gradlew check`，确认 `validateConstraints` 被执行。
- 文档检查：`git diff --check`，并人工核对 Docker 当前实现索引与已知偏差。
- 本计划不触发 Docker HTTP 回归，因为不修改运行时文件或行为。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 12.1 | 校准 Docker 当前事实与受控偏差 | ✅ 完成 | ada6f9c | 2026-06-19 |
| 12.2 | 收敛现行约束术语与路由 | ✅ 完成 | ada6f9c | 2026-06-19 |
| 12.3 | 实现约束校验器并接入 Gradle | ✅ 完成 | ada6f9c | 2026-06-19 |
| 12.4 | 同步依赖计划、执行队列并完成验收 | ✅ 完成 | ada6f9c | 2026-06-19 |

整体进度：4 / 4（100%）

四项治理任务紧密耦合，共享实现提交 `ada6f9c`：约束文档、校验器、Gradle 接入与计划依赖同步共同构成一个可独立验证和回滚的防漂移护栏。

## 12.1 校准 Docker 当前事实与受控偏差

**目标**：让 Docker 约束准确表达当前 Compose、镜像和配置事实，同时把尚未完成的部署治理明确登记为偏差。  
**前置任务**：无  
**范围**：重写 `constraints/docker-structure.md` 的文档定位、适用范围、硬约束、当前文件与服务索引、依赖关系、端口、挂载、运行身份和已知偏差；当前索引必须覆盖 `db`、`model-init`、`sidecar`、`app`、`app-smoke`，并明确 App 尚无正式健康检查等由 `plan_10` 负责的偏差。  
**非目标**：不修改 `docker-compose.yml`、Dockerfile、应用配置、健康端点或启动命令。  
**验收标准**：文档中的当前事实可逐项在仓库实现中找到；未来目标只出现在硬约束或已知偏差中；`plan_10` 的实现职责未被提前宣称完成。  
**验证方式**：逐项对照 `docker-compose.yml`、`Dockerfile`、`sidecar/Dockerfile`、`crag-app/src/main/resources/application.yml`；运行 `docker compose config --services` 与文档索引人工核对；运行 `git diff --check`。  
**涉及文件**：`constraints/docker-structure.md`

## 12.2 收敛现行约束术语与路由

**目标**：消除无期限“迁移期”表述，并固定现行入口的单一语义。  
**前置任务**：12.1  
**范围**：将 `constraints/package-structure.md`、`constraints/persistence-style.md` 中 Storage “迁移期例外”改为“受控架构例外”；说明现有白名单、退出触发条件和不主动形式化迁移的原因；核对 `AGENTS.md` 与 `CLAUDE.md` 路由内容完全一致。  
**非目标**：不调整 Storage API、DAO、Entity、架构测试白名单或普通业务术语 `AdminRag`。  
**验收标准**：现行约束不再使用“迁移期例外”；受控例外仍禁止 Repository 外泄和新增 Entity 传播；两个入口文件完全一致且只维护路由。  
**验证方式**：运行 `cmp AGENTS.md CLAUDE.md`；运行 `rg -n '迁移期例外|非单元测试' AGENTS.md CLAUDE.md constraints` 并确认无禁止命中；人工核对 Storage 边界语义未放宽。  
**涉及文件**：`constraints/package-structure.md`、`constraints/persistence-style.md`、`AGENTS.md`、`CLAUDE.md`

## 12.3 实现约束校验器并接入 Gradle

**目标**：用可独立执行、可测试的机械护栏阻止现行约束再次漂移。  
**前置任务**：12.1、12.2  
**范围**：新增 `scripts/validate_constraints.py`，实现四类检查：入口文件完全一致；现行 Markdown 相对链接存在；动态读取 Compose 服务并核对 Docker 当前实现索引；扫描明确废弃术语及允许上下文。新增 `scripts/tests/test_validate_constraints.py`，使用临时目录构造通过和失败样例。根 Gradle 新增 `validateConstraints` 并让 `check` 依赖它。  
**非目标**：不解析历史 Plan，不引入 PyYAML，不判断语义职责，不替代现有 Plan、模块或 ArchUnit 校验。  
**验收标准**：每类规则至少有一个通过用例和一个失败用例；失败输出包含稳定错误码、文件和原因；独立脚本对当前仓库返回 0；`./gradlew check` 明确执行 `validateConstraints`。  
**验证方式**：先增加失败测试并运行 `python3 -m unittest scripts.tests.test_validate_constraints` 确认失败；实现最小校验逻辑后重跑至通过；再运行 `python3 scripts/validate_constraints.py` 与 `./gradlew check`。  
**涉及文件**：`scripts/validate_constraints.py`、`scripts/tests/test_validate_constraints.py`、`build.gradle.kts`

## 12.4 同步依赖计划、执行队列并完成验收

**目标**：让本轮 grilling 形成的后续工作与顺序成为仓库事实，并完成计划级验收。  
**前置任务**：12.3  
**范围**：核对并完善 `plan_9.hotfix_3`；将 `plan_7` 的执行前置更新为 `plan_9.hotfix_3`，说明其通过 `plan_12` 获得约束基线；保持 `plan_10` 对 `plan_7` 的直接依赖并说明完整上游链；同步索引与执行队列；运行全部计划和工程校验并记录证据。  
**非目标**：不执行 `plan_9.hotfix_3`、`plan_7` 或 `plan_10` 的实现任务。  
**验收标准**：执行队列唯一为 `plan_12 → plan_9.hotfix_3 → plan_7 → plan_10`；显式依赖无环；两个新计划均完整；所有校验通过且本计划没有未提交实现改动。  
**验证方式**：运行 `python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py --strict --verify-git`、`python3 scripts/validate_module_dependencies.py`、`./gradlew check` 和 `git diff --check`；核对索引状态与进度。  
**涉及文件**：`plan/plan_12/plan_12.md`、`plan/plan_9/plan_9.hotfix_3.md`、`plan/plan_7/plan_7.md`、`plan/plan_10/plan_10.md`、`plan/index/README.md`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-19 | macOS, Python 3.14 | `python3 -m unittest scripts.tests.test_validate_constraints -v` | ✅ 通过 | 13/13 测试通过，覆盖入口一致（含 LF/CRLF 字节级）、链接、Compose 解析失败、crag-admin 上下文和废弃术语的通过与失败用例 |
| 2026-06-19 | macOS, Python 3.14 | `python3 scripts/validate_constraints.py` | ✅ 通过 | 0 错误：AGENTS/CLAUDE 一致、链接完整、Compose 5 服务已登记、无废弃术语 |
| 2026-06-19 | macOS, Python 3.14 | `python3 scripts/validate_module_dependencies.py` | ✅ 通过 | 0 错误：模块依赖无环且在白名单内 |
| 2026-06-19 | macOS, Python 3.14 | `python3 scripts/validate_plans.py --strict --verify-git` | ✅ 通过 | 0 错误，24 警告均为历史 Plan 格式；实现提交哈希存在且唯一 |
| 2026-06-19 | macOS, Gradle 9.4.1 | `./gradlew check` | ✅ 通过 | 全部 47 任务执行：validateConstraints / validatePlans / validateModuleDependencies / 所有子项目 check |
| 2026-06-19 | macOS | `git diff --check` | ✅ 通过 | 无空白问题 |

## 阻塞记录

无。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | 创建并转为 ready | constraints 改造复核与 grilling 已完成，范围、依赖、校验边界和回滚均已确定 | 建立 4 项治理任务；执行后进入 plan_9.hotfix_3 |
| 2026-06-19 | 执行 4 项任务 | 完成 Docker 约束重写、术语收敛、校验器实现与 Gradle 接入、计划同步和全量验收 | constraints/docker-structure.md 覆盖全部 5 个 Compose 服务；迁移期例外→受控架构例外；新增 validateConstraints 校验器及 13 个测试（含 CRLF 字节级、Compose 解析失败、crag-admin 上下文）；AGENTS/CLAUDE 一致；所有校验通过 |
| 2026-06-19 | 验收通过并完成 | 实现提交 `ada6f9c` 已核对范围，全部完成门槛通过 | 4 项任务回填实现提交并标记完成；执行队列推进至 plan_9.hotfix_3 |
