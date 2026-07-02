# CRAG Web Console — 工程索引

`web/` 是 CRAG-Demo 的 React + TypeScript 前端工程。本文件是工程治理路由，不重复展开细则。

## 计划状态

`web/` 不维护独立 Plan 目录。所有计划创建、状态、执行和验收仍遵守根目录 `constraints/plan-workflow.md`，并维护在根目录 `plan/`。查询计划状态时查看 `plan/index/README.md`。

## 约束路由

- 架构与 MVVM 分层：`web/constraints/architecture.md`
- 代码风格：`web/constraints/code-style.md`
- UI 与响应式：`web/constraints/ui-style.md`
- API Client 与认证安全：`web/constraints/api-client.md`
- 测试工作流：`web/constraints/test-workflow.md`
- Plan 状态与提交协议：根目录 `constraints/plan-workflow.md`

## 技术栈

React 19.2、TypeScript strict、Vite 8、Ant Design 6、`@ant-design/icons` 6、React Router、TanStack Query、React Hook Form、Zod、Vitest、React Testing Library、MSW、Playwright。禁止引入 Ant Design Pro、Redux 或额外全局状态库。

## 常用命令

详见 `web/README.md` 与 `web/constraints/test-workflow.md`。

## 对话约定

- 涉及 MVVM 分层、依赖方向或 feature 目录时，遵守 `web/constraints/architecture.md`。
- 涉及 TypeScript、命名或格式时，遵守 `web/constraints/code-style.md`。
- 涉及组件、状态色或响应式时，遵守 `web/constraints/ui-style.md`。
- 涉及 transport、认证或安全红线时，遵守 `web/constraints/api-client.md`。
- 涉及测试命令、MSW 或 Playwright 时，遵守 `web/constraints/test-workflow.md`。
- 涉及计划创建、执行或验收时，遵守根目录 `constraints/plan-workflow.md`。
