# CRAG Web Console UI 交接清单

## 1. 当前状态

- 阶段：Stitch 首版评审与调整
- Stitch 源文件：Google Stitch MCP，项目 `CRAG`
- Stitch Project ID：`1781302515522825622`
- 当前设计版本：Stitch draft v1（尚未批准）
- 最近更新：2026-07-02
- 需求输入：`docs/product/web-console-stitch-prd.md`
- 技术边界：`docs/superpowers/specs/2026-07-02-web-console-design.md`

本文件是 UI 设计跨 Session 交接的首要入口。开始 UI 或前端实现前，应先读取本文件、Stitch PRD 和前端设计说明。

## 2. MCP 与本地同步工作流

Google Stitch MCP 是评审期间的设计编辑工作区，仓库是已批准设计的实现基线。默认流程固定为：

1. 仅在需要盘点、评审或调整时通过 MCP 读取项目和目标 Screen，避免每个 Session 重复拉取全部内容。
2. 所有 UI 调整通过 MCP 在 Stitch 项目中完成；中间版本只记录调整原因和目标 Screen，不同步到仓库。
3. 用户确认当前版本定稿后，再通过 MCP 读取最终 Screen 与 Design System。
4. 同一次提交中将最终截图、必要参考 HTML 和 `DESIGN.md` 同步到 `docs/product/ui/stitch-vN/`，更新本文件的版本、Screen ID、批准状态和变更记录。
5. 后续实现只以仓库中最近一次已批准快照为视觉基线；Stitch 中未批准的新修改不得静默覆盖实现依据。

MCP 调整记录至少包含日期、目标 Screen ID、调整目的、结果和是否定稿。批量调整应先读取一次项目清单，再集中执行，避免逐页重复发现调用。

## 3. 产物管理规则

Stitch 保留可编辑的设计源文件，仓库保留交接信息和可追溯快照：

- Stitch Project ID、Screen ID、版本和更新时间记录在本文件。
- 评审通过的关键页面导出到 `docs/product/ui/`。
- 导出文件使用 `<page>-<viewport>-v<version>.<ext>` 命名，例如 `knowledge-list-desktop-v1.png`。
- 设计 Token、组件说明或交互标注以 Markdown、JSON、PNG 或 PDF 保存，不提交仅能由特定工具读取的临时缓存。
- 每次设计变更都要更新页面状态、关键决策和变更记录，并与对应资产一起提交。
- 仓库快照用于实现和审查；发生冲突时，以本文件记录的最近一次“已批准”本地版本为准，评审中的 Stitch draft 不覆盖它。

## 4. 页面交付状态

| 页面/组件 | 桌面端 | 移动端 | 交互状态 | 仓库快照 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 登录 | 待评审 | 待设计 | 待设计 | 未同步 | Stitch Screen `066faedc1fbd41dd963402bd4fc0fb55` |
| 注册 | 待设计 | 待设计 | 待设计 | 无 | 包含字段校验 |
| 应用 Shell/导航 | 待评审 | 待设计 | 待设计 | 未同步 | 首版桌面稿已有统一 Shell |
| Knowledge 列表 | 待评审 | 待设计 | 待设计 | 未同步 | Stitch Screen `5ce43c38c14d4d768d5041bd2c5383a8` |
| Knowledge 详情 | 待评审 | 待设计 | 待设计 | 未同步 | 当前只覆盖 Documents 页签 |
| Documents | 待评审 | 待设计 | 待评审 | 未同步 | Stitch Screen `63a666358c8f4016b009250f156274d5` |
| API Keys 独立索引 | 待评审 | 非首版必需 | 待设计 | 未同步 | Stitch Screen `ac7f8e5f4e7e4c93838dad3aa0da732d` |
| 一次性 API Key 模态框 | 待设计 | 响应式适配 | 待设计 | 无 | 显隐、复制、确认已保存 |
| Chat | 待评审 | 待设计 | 待评审 | 未同步 | 初始 `495eb38da9294e2a873f8c50c4f3b841`；对话 `57e0c3a192844f48be7d7239d03c7626` |

状态统一使用：`待设计`、`设计中`、`待评审`、`已批准`、`需修改`。只有 `已批准` 的页面才能作为 UI 实现验收基线。

## 5. 已确认设计决策

1. 产品采用管理后台 MVP 加独立 Chat 调试器。
2. 桌面端以固定侧边导航和顶部工具栏为主，移动端使用折叠导航。
3. 使用 Ant Design 设计语言，保持工作型后台的信息密度。
4. Knowledge 是管理主线，API Keys 同时提供知识库内视图和独立索引。
5. Chat 不要求输入知识库 ID，知识库由 API Key 决定。
6. 完整 API Key 只展示一次，不在设计中提供再次查看入口。
7. 页面必须覆盖成功、空、加载和主要失败状态。
8. 不设计后端尚未提供的统计、删除、聊天历史或租户级 API Key 搜索能力。

## 6. 当前首版评审修正规则

首版稿的布局、密度、色彩、排版和组件风格可作为调整基础，但功能内容必须以 PRD 与 OpenAPI 为准。定稿前必须从页面中移除或改写后端不支持的内容：

- 登录页不提供 Remember me、Forgot password、Privacy Policy 或 Terms of Service 假入口。
- Knowledge 不展示文件容量、Token、请求量、系统健康、编辑等无契约能力。
- Documents 上限改为 10 MiB；不提供删除、Token 统计、Sync All、Chunking Strategy 或批量上传承诺。
- API Keys 不展示请求统计、Security Status 或 All Access Key；Key 必须归属单个 KnowledgeBase。
- Chat 不展示 GPT 模型、默认 KnowledgeBase、附件和后端未返回的页码/章节；来源只使用 reference、documentId、excerpt。
- 补齐注册、一次性 Key 模态框、移动端关键页和主要加载/空/失败状态。

## 7. 定稿同步动作

用户确认 Stitch 当前版本定稿后，在同一次更新中完成以下事项：

1. 填写 Stitch 源文件链接、设计版本和更新时间。
2. 将页面状态更新为 `待评审`，并记录未覆盖的状态。
3. 导出桌面与移动端关键页面快照到 `docs/product/ui/`。
4. 记录与 PRD 不一致的设计决定及原因。
5. 用户确认后将对应页面标记为 `已批准`。
6. 在变更记录中写明版本、范围和对应提交。

## 8. 实现交接检查

开始实现某页面前确认：

- 页面状态是 `已批准`，或 Plan 明确允许先实现无视觉依赖的骨架。
- 设计中使用的组件可以由 Ant Design 和项目公共组件实现。
- 页面所需字段与 `docs/api/` 中的 OpenAPI 契约一致。
- 空态、加载态、失败态、禁用条件和危险操作确认均有明确表现。
- 桌面和移动布局没有文本溢出、操作遮挡或依赖横向滚动的核心流程。

## 9. MCP 调整记录

| 日期 | Screen | 调整目的 | 结果 | 定稿 |
| --- | --- | --- | --- | --- |
| 2026-07-02 | Project `1781302515522825622` | 建立 MCP 设计源与本地批准快照分工 | 已读取项目与 6 个 Screen 清单 | 否 |

## 10. 变更记录

| 日期 | 版本 | 变更 | 提交 |
| --- | --- | --- | --- |
| 2026-07-02 | 需求基线 v1 | 建立 UI 交接机制，等待 Stitch 首版设计稿 | 当前文档提交 |
| 2026-07-02 | Stitch draft v1 | 记录 MCP 项目、Screen 清单、首版缺口和按定稿同步规则 | 工作流更新 |
