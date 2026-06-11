# Plan_2.3 — Git ignore 本地噪音清理

> 创建时间：2026-06-11
> 范围：检查 Git 状态中的本地/工具生成内容，补充忽略规则，避免误提交。

---

## 范围说明

本计划只处理 Git ignore 规则与状态检查：

- 检查当前 `git status --short` 与 `git status --ignored --short`
- 确认已忽略的构建产物、模型缓存、数据库数据目录与 Python 缓存
- 将 Claude 本地配置目录 `.claude/` 加入 `.gitignore`
- 确认 Gradle wrapper jar 不被通用 `*.jar` 规则误忽略
- 验证 `.claude/` 不再作为未跟踪内容出现在 Git 状态中

不修改 Java 业务代码、Docker 编排、Sidecar 实现或数据库 schema。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 2.3.1 | 检查当前 Git 状态与忽略列表 | ✅ 完成 | — | 2026-06-11 |
| 2.3.2 | 补充 `.claude/` 忽略规则 | ✅ 完成 | — | 2026-06-11 |
| 2.3.3 | 验证 Git 状态中 `.claude/` 已被忽略 | ✅ 完成 | — | 2026-06-11 |
| 2.3.4 | 修正 Gradle wrapper jar 忽略规则顺序 | ✅ 完成 | — | 2026-06-11 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**4 / 4（100%）**

---

## 检查结论

当前已通过 `.gitignore` 覆盖的本地噪音包括：

- Gradle 缓存与构建产物：`.gradle/`、`build/`
- 本地模型缓存：`.models/`
- 数据库本地数据：`data/*`
- Python 缓存：`__pycache__/`、`*.pyc`
- 本地环境配置：`.env`、`.env.local`、`application-local.yml`、`application-local.properties`

待补充：

- `.claude/` 目录已作为本地 AI 工具配置目录整体忽略，避免 `.claude/settings.json` 等文件误进入提交。
- `gradle/wrapper/gradle-wrapper.jar` 是 Gradle wrapper 的必要文件，不应被通用 `*.jar` 规则误忽略；已在 Java ignore 规则后补充 `!gradle/wrapper/gradle-wrapper.jar`。

验证结果：

- `git check-ignore -v .claude/settings.json .claude/settings.local.json` 命中 `.gitignore` 中的 `.claude/` 规则。
- `git check-ignore -v gradle/wrapper/gradle-wrapper.jar` 命中 `.gitignore` 中的保留例外规则。
- `git status --short` 中 `.claude/` 已不再作为未跟踪目录出现。
