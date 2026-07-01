# CRAG Web Console 前端设计说明

## 1. 背景与目标

CRAG 当前已经提供 Console API 与 Open API，需要增加独立前端工程，形成从注册登录、知识库与文档管理、API Key 管理到知识检索对话的完整用户闭环。

本设计确定以下交付物：

1. 可交给 Stitch 生成 Web 设计稿的产品 PRD。
2. 位于 `web/` 的 React + Ant Design 前端脚手架。
3. 前端独立的工程与代码约束。
4. 遵循根目录 Plan 工作流的后续功能迭代路径。

## 2. 已选择方案

采用“管理后台 MVP + Chat 调试器”方案。

该方案以 Knowledge 为管理主线，以 API Keys 提供跨知识库索引，并将 Chat 作为独立验证工具。它比完整 Ant Design Pro 控制台更轻，避免引入当前阶段不需要的权限菜单和平台化能力；同时比单页 Knowledge 工作台具有更清晰的长期导航结构。

## 3. 产品范围

### 3.1 路由与导航

登录后主导航包括 Knowledge、API Keys、Chat、Account。

- `/login`
- `/register`
- `/app/knowledge`
- `/app/knowledge/:knowledgeBaseId`
- `/app/api-keys`
- `/app/chat`

Knowledge 详情包含 Overview、Documents、API Keys 页签。

### 3.2 功能范围

- 注册、登录、刷新会话和退出。
- 创建、列表与查看知识库。
- 上传 `.txt`、`.md` 文档，轮询摄取状态，并在允许时重试。
- 在 Knowledge 内和独立索引页管理 API Key。
- 创建、启用、禁用、轮换和撤销 API Key。
- 使用临时输入的 API Key 发起 Open API 问答并展示来源。

当前不包含成员与租户管理、知识库删除、文档删除、聊天历史、使用统计。后端没有稳定契约的能力不得在 UI 中伪造。

当前 API Key 只提供知识库级列表接口。独立索引页在 MVP 中通过 Knowledge 分页结果和知识库级 Key 列表组合展示，并限制并发请求；不得假装存在服务端全局搜索或排序。实现 Plan 必须定义聚合上限、部分失败显示和请求并发策略，数据规模扩大后再评估租户级索引 API。

## 4. 关键业务规则

- Access Token 只保存在前端内存，Refresh Token 由 HttpOnly Cookie 管理。
- 页面刷新后通过刷新接口恢复会话；并发 401 只触发一次 refresh，成功后请求最多重放一次。
- `apiKeyReady=false` 时允许上传文档，但禁止创建 API Key，并轮询知识库状态。
- 文档上传成功返回异步任务语义，前端轮询至 READY 或 FAILED。
- 只有错误响应声明可重试时才提供文档重试操作。
- 完整 API Key 仅在创建或轮换成功后显示一次，不写入持久化浏览器存储。
- Chat 使用的 API Key 仅保存在当前页面内存，刷新后清除。
- Open API 请求只携带 API Key 和问题，不由 UI 推断或提交租户、知识库 ID。

## 5. 技术方案

### 5.1 基础技术栈

- React 19、TypeScript、Vite
- Ant Design、`@ant-design/icons`
- React Router
- TanStack Query
- React Hook Form、Zod
- Vitest、React Testing Library、MSW
- Playwright
- ESLint、Prettier、TypeScript strict
- pnpm 与 Node LTS

采用普通 Ant Design 应用骨架，不引入完整 Ant Design Pro。若实现阶段发现 React 19 与仓库锁定依赖存在兼容问题，应在 Plan 中记录并选择受支持的稳定版本，不改变本设计的模块边界。

### 5.2 工程目录

```text
web/
├── src/
│   ├── app/              # 启动、路由、Provider
│   ├── pages/            # 页面 View
│   ├── features/         # 业务能力
│   │   ├── auth/
│   │   ├── knowledge/
│   │   ├── documents/
│   │   ├── api-keys/
│   │   └── chat/
│   ├── entities/         # 领域类型与展示模型
│   ├── services/         # API Client 与会话
│   ├── shared/           # 通用 UI 和工具
│   └── test/             # 测试基础设施
├── constraints/
├── AGENTS.md
├── CLAUDE.md
└── README.md
```

每个 feature 根据需要使用 `api/`、`model/`、`view-model/`、`components/` 和 `tests/`。简单能力不为追求目录一致性创建空层级。

## 6. MVVM 与依赖约束

### 6.1 View

`pages/` 和 `components/` 负责展示、布局、无业务含义的交互状态和调用 ViewModel 命令。View 不直接发起 HTTP 请求，不解析 API 错误码，不实现业务状态机。

### 6.2 ViewModel

ViewModel 使用 hooks 暴露页面所需的展示状态、查询结果和命令，编排 TanStack Query、表单提交、轮询、导航和反馈。离开页面时必须停止不再需要的轮询。

### 6.3 Model 与 API

Model 保存业务规则、领域类型和纯转换逻辑。API 层只负责协议交互。HTTP DTO 必须经过映射后才能进入 View，避免协议字段渗透到组件。

### 6.4 模块依赖

- `shared` 不依赖业务 feature。
- feature 通过公开入口被页面或其他 feature 使用。
- 禁止跨 feature 导入内部文件。
- Console API 与 Open API 使用独立客户端和认证策略。
- 可复用 UI 只承载展示能力，不接受隐藏业务判断的布尔参数组合。

## 7. 状态、错误与安全

统一将 API 错误适配为：字段错误、业务错误、认证失效、权限不足、可重试错误和未知错误。保留 `traceId` 供排查，但不以原始响应结构驱动页面。

TanStack Query 管理服务端状态；短暂的输入、模态框和显隐状态留在组件或 ViewModel。不要复制 Query 数据到全局 Store。当前范围不引入额外状态管理库。

敏感信息要求：

- 不记录 Token、完整 API Key 或用户密码。
- 完整 API Key 不进入 URL、日志、分析事件或持久化缓存。
- 一次性密钥模态框关闭前要求用户确认已保存。
- 退出登录时清理内存会话和查询缓存。

## 8. Web 独立约束

`web/AGENTS.md` 作为前端规则索引，`web/CLAUDE.md` 指向同一组约束，避免两套规则漂移。具体文件至少包括：

- `web/constraints/architecture.md`
- `web/constraints/code-style.md`
- `web/constraints/ui-style.md`
- `web/constraints/api-client.md`
- `web/constraints/test-workflow.md`

`web` 不建立独立 Plan 目录。所有计划创建、状态和执行仍遵循根目录 `constraints/plan-workflow.md`，并维护在根目录 `plan/`。

## 9. 测试与验收

基础质量门禁：

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

单元测试覆盖纯映射、状态判断和 ViewModel 规则；组件测试使用 MSW 覆盖 API 成功和失败；Playwright 覆盖注册/登录、Knowledge、Documents、API Keys 和 Chat 的核心流程。

登录、Knowledge、API Key 与 Chat 至少验证成功态、空态、加载态和主要失败态。最终联调连接本地 Console API `8080` 与 Open API `8081`，测试环境默认不依赖真实后端。

## 10. 迭代顺序

后续实现计划按纵向能力推进：

1. 脚手架、规范、应用 Shell 和质量门禁。
2. 双 API Client、错误适配、认证与受保护路由。
3. Knowledge 列表、创建、详情和就绪轮询。
4. 文档上传、状态轮询、失败展示和重试。
5. Knowledge 内及独立页面的 API Key 管理。
6. Chat 问答、引用来源和错误恢复。
7. 根据 Stitch 设计稿完成视觉收敛、响应式与端到端验收。

根目录实施 Plan 应为每一阶段写明后端契约、测试证据、文档更新和完成条件。脚手架可以先于最终 Stitch 视觉稿建立，但业务页面的视觉定稿应以获批设计稿为准。

## 11. 设计产物

Stitch 输入文档位于 `docs/product/web-console-stitch-prd.md`。该文档定义页面、状态、响应式要求和设计输出格式；本说明定义技术架构和实施边界。两者共同作为后续 Plan 的需求基线。

UI 设计使用 `docs/product/web-console-ui-handoff.md` 跨 Session 交接。Stitch 保存可编辑源文件，`docs/product/ui/` 保存已评审页面的导出快照、Token 和必要标注。每次设计变更必须同步更新源文件版本、页面批准状态、关键决策和变更记录；只有标记为已批准的页面才作为 UI 实现验收基线。

## 12. Docker 部署

Web 必须纳入根目录 `docker-compose.yml`，使 `docker compose up -d --build` 能构建并启动完整 Demo。默认浏览器入口为 `http://localhost:3000`，不要求用户在宿主机单独安装或启动 Node。

### 12.1 镜像与运行方式

`web/Dockerfile` 使用固定 Node 22 Alpine 标签进行多阶段构建：构建阶段安装锁定依赖并生成 `dist/`；运行阶段只保留生产运行依赖、静态产物和轻量 Node Server。容器使用 Node 镜像自带的非 root `node` 用户，以 `npm start` 启动并监听 `0.0.0.0:3000`。

`npm start` 不得使用 `vite preview`。运行时 Server 仅承担部署职责：静态文件服务、SPA 路由回退、同源 API 代理和健康检查，不承载前端业务逻辑。

### 12.2 同源代理

浏览器只访问 Web 容器：

- `/console-api/*` 转发至 `http://console-api:8080/*`。
- `/open-api/*` 转发至 `http://open-api:8081/*`。

代理必须剥离外部前缀并保留请求方法、查询参数、Authorization、Origin 和上传流。Console API 返回的 Refresh Cookie Path `/api/v1/auth` 必须重写为 `/console-api/api/v1/auth`，确保浏览器后续 refresh/logout 请求携带 HttpOnly Cookie。前端 API Client 使用相对地址，不保存容器服务名或宿主机 API 端口。

Demo Compose 必须将 `http://localhost:3000` 配置为 Console API 的允许 Origin，并为本地 HTTP 显式设置 `crag.console.cookie.secure=false`。该降级只适用于本地与 Demo；HTTPS 部署必须恢复 Secure Cookie，并将允许 Origin 改为实际 Web 地址。

代理错误返回明确的 502/503 响应，不回显 Token、API Key 或下游内部细节。文档上传代理限制不得低于后端允许的 10 MiB，并应保留少量协议开销余量。

### 12.3 Compose 与健康检查

Compose 新增 `web` 服务并映射 `3000:3000`，加入 `crag-net`。`web` 对 Console API 和 Open API 使用健康依赖；运行时 Server 自身提供 `/health`，健康检查验证静态服务进程可响应。

Web 配置通过环境变量注入下游地址，Compose 默认使用服务名 `console-api` 和 `open-api`。镜像不挂载源码、`dist/` 或宿主机配置文件来覆盖构建内容。

实施时必须同步更新：

- `docker-compose.yml`
- `constraints/docker-structure.md`
- 根目录 `.dockerignore` 或 `web/.dockerignore`
- `.env.example`（仅当新增可配置项）
- 根目录 README 的启动入口与访问地址

### 12.4 部署验收

Docker 验收至少覆盖：

1. 全新环境执行 `docker compose up -d --build` 后 Web 容器健康。
2. 直接访问深层 SPA 路由不会返回 404。
3. 通过 Web 入口完成注册、登录、刷新会话和退出。
4. Console API 文件上传和 Open API Chat 均通过同源代理工作。
5. 浏览器请求不直接依赖宿主机的 `8080/8081` 端口。
