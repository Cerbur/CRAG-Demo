# Web 架构约束

> 前端分层与依赖规则。本文件只路由规则，不重复根目录 `constraints/plan-workflow.md` 的计划状态规则。

## 1. MVVM 分层

Web 工程采用 MVVM 分层，目录固定为 `web/src/{app,pages,features,entities,services,shared,test}`。

### 1.1 View

- 范围：`pages/**`、`features/<name>/components/**`、`shared/ui/**`。
- 职责：展示、布局、无业务含义的交互状态（输入焦点、模态显隐）和调用 ViewModel 命令。
- View **禁止**：
  - 直接发起 HTTP 请求或导入 `services/http/**`。
  - 解析 API 错误码或字段错误结构。
  - 实现业务状态机、轮询逻辑或并发 refresh。
  - 直接消费后端 DTO 字段（必须经 mapper 转成 Model）。

### 1.2 ViewModel

- 范围：`features/<name>/view-model/**`，通常以 `useXxxViewModel` hook 暴露。
- 职责：编排 TanStack Query/Mutation、React Hook Form、Zod 校验、轮询、导航和反馈。
- 离开页面时必须停止不再需要的轮询；并发 401 依赖 `services/http` 的 single-flight refresh。

### 1.3 Model 与 API

- 范围：`features/<name>/model/**`（领域类型与纯转换）、`features/<name>/api/**`（协议交互）、`entities/**`（跨 feature 共享类型）、`services/http/**`（transport 与 client）。
- HTTP DTO 必须经 mapper 转成 Model 后才能进入 View；禁止协议字段渗透到组件 props。

## 2. feature 目录约定

每个 feature 根据实际需要使用 `api/`、`model/`、`view-model/`、`components/`、`tests/`。简单能力不为追求目录一致性创建空层级。

## 3. 依赖方向

- `shared` 不依赖任何业务 feature。
- feature 通过公开入口被页面或其他 feature 使用。
- **禁止跨 feature 内部导入**：`features/auth/**` 不得直接导入 `features/knowledge/**` 内部文件，反之亦然。共用能力放回 `shared` 或 `entities`。
- Console API 与 Open API 使用独立 client（`services/http/console-client`、`services/http/open-client`）和不同认证策略。
- 可复用 UI 只承载展示能力，不接受隐藏业务判断的布尔参数组合。

## 4. 校验

`tests/architecture.import-boundaries.test.ts` 通过源码扫描断言：

- `pages/**`、`features/**/components/**` 不导入 `services/http`。
- feature 不导入另一 feature 内部文件。

ESLint `eslint.config.js` 的 `no-restricted-syntax` 提供等价的失败保护，防止违规提交进入仓库。
