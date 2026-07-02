---
workflow_version: 3
plan_id: plan_22
type: main
status: verifying
created: 2026-07-02
updated: 2026-07-02
---

# plan_22 — Web Console 与开箱即用部署

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans` task-by-task. **Project override:** before either, read and follow `skill/execute-crag-plan/SKILL.md`; its task status、实现提交和独立验收交接规则优先。

**Goal**：交付可注册登录、管理 Knowledge/Document/API Key、执行知识检索对话，并能随完整 Docker Compose 开箱启动的 Web Console。

**Architecture**：`web/` 是独立 React + TypeScript 工程，按 feature 内的 API、Model、ViewModel、View 分层；HTTP DTO 经 mapper 转换，页面组件不直接请求 API。Node Runtime Server 只托管静态文件、SPA 回退、健康检查和两个同源代理，不承载业务逻辑。

**Tech Stack**：Node 22.12+、pnpm、React 19.2、TypeScript strict、Vite 8、Ant Design 6、React Router、TanStack Query、React Hook Form、Zod、Vitest、React Testing Library、MSW、Playwright、Node HTTP proxy runtime、Docker Compose。

## 全局实现约束

- 设计事实来源：`docs/superpowers/specs/2026-07-02-web-console-design.md`；Stitch 输入：`docs/product/web-console-stitch-prd.md`；UI 状态：`docs/product/web-console-ui-handoff.md`。
- 设计提交：`b3e15dac`、`de7327c6`、`43a72b1`；方向变更记录：`plan/plan_archive/2026-07-02-web-console-full-stack-demo.md`。
- 执行前读取 `skill/execute-crag-plan/SKILL.md`、本 Plan、`plan/index/README.md`、`constraints/plan-workflow.md`、`constraints/docker-structure.md` 和 `web/AGENTS.md`。
- 前端根目录固定为 `web/`；`web/AGENTS.md` 和 `web/CLAUDE.md` 必须字节一致，只路由具体约束。
- React UI 使用 Ant Design 6 与 `@ant-design/icons` 6；不引入 Ant Design Pro、Redux 或额外全局状态库。
- View 不直接发 HTTP、不解析 API 错误码、不实现业务状态机；ViewModel 编排 Query/Mutation/表单/导航；Model 和 mapper 承载业务规则与 DTO 转换。
- Access Token 只在内存；Refresh Token 只由 HttpOnly Cookie 管理；完整 API Key、密码和 Token 不进入日志、URL、持久化缓存或测试快照。
- Console/Open API Client 使用相对前缀 `/console-api`、`/open-api`；Open Query 不提交 tenantId 或 knowledgeBaseId。
- 轮询离开页面后停止；并发 401 只发起一次 refresh，每个失败请求最多重放一次。
- UI 必须覆盖成功、空、加载和主要失败状态；移动端核心流程不依赖横向滚动。
- Stitch MCP 是评审期设计编辑源；只在用户确认定稿后将最终 Screen、必要参考 HTML 和 Design System 一次性同步到仓库，执行细节遵守 `docs/product/web-console-ui-handoff.md`。
- 依赖通过 `pnpm-lock.yaml` 锁定；Node/Vite/React/Ant Design 大版本升级不在本 Plan 内。
- 代码与验证分别遵守 `web/constraints/*.md`、根目录 Docker/Plan/Test 约束；Web 计划仍只维护在根目录 `plan/`。

## 背景与目标

`plan_21` 已交付 Console API `8080`、Open API `8081` 和两份 OpenAPI 3.1 契约，但仓库没有浏览器客户端。用户需要借助脚本或手工 HTTP 才能注册、建库、上传、管理 Key 和查询，不能形成可展示的产品闭环。

本 Plan 新增独立 Web 工程、前端治理、MVVM 业务切片、自动化测试和 Node Docker 运行层。最终执行 `docker compose up -d --build` 后，只访问 `http://localhost:3000` 即可使用完整核心流程。

## 范围

- React + TypeScript + Ant Design 工程、质量门禁、应用 Shell 和响应式导航。
- Web 独立 `AGENTS.md`、`CLAUDE.md` 与 architecture/code/UI/API/test 约束。
- Console/Open DTO、mapper、双 Client、统一错误、认证 Session 与受保护路由。
- 注册、登录、Knowledge、Documents、API Keys、Chat 页面及 ViewModel。
- API Key 独立索引的有界并发聚合和部分失败表达。
- Stitch 设计稿交接、已批准页面视觉落地与桌面/移动端验收。
- Node `npm start` Runtime Server、同源代理、Cookie Path 重写、Dockerfile、Compose 和健康检查。
- 单元、组件、浏览器和 Docker 全链路回归。

## 非目标

- 不实现成员/租户管理、Knowledge/Document 删除、聊天历史、用量统计、计费、MFA 或找回密码。
- 不新增或修改后端业务 API，不为 API Key 独立页伪造租户级搜索/排序接口。
- 不使用 Next.js、SSR、React Server Components、Ant Design Pro、Nginx 或 Kubernetes。
- 不把业务逻辑放入 Node Runtime Server，不在浏览器保存完整 API Key。
- 不要求等待最终 Stitch 视觉稿才建立无视觉依赖的脚手架、API 和业务测试。

## 前置依赖

- **执行前置 Plan**：`plan_21`
- `plan_21` 已独立验收完成，两份 OpenAPI 和中文 API 指南是协议事实来源。
- 用户已复核 Web 产品、架构、UI 交接和 Node Docker 部署设计。

## 文件边界

- `web/**`
- `docker-compose.yml`
- `.dockerignore`
- `.env.example`
- `README.md`
- `docs/README.md`
- `docs/product/**`
- `constraints/docker-structure.md`
- `constraints/test-workflow.md`
- `scripts/validate_constraints.py`
- `scripts/tests/test_validate_constraints.py`
- `scripts/tests/http/web_*.sh`
- `plan/plan_22/plan_22.md`
- `plan/index/README.md`
- `plan/plan_main.md`
- `plan/plan_archive/2026-07-02-web-console-full-stack-demo.md`

## 实现文件地图

### 工程与规则

- `web/package.json`、`pnpm-lock.yaml`：依赖、`dev/build/start/lint/typecheck/test/e2e` 命令。
- `web/vite.config.ts`、`tsconfig*.json`、`eslint.config.js`、`.prettierrc.json`：构建与质量门禁。
- `web/AGENTS.md`、`web/CLAUDE.md`、`web/constraints/*.md`：MVVM、代码、UI、API 与测试规则。
- `web/src/app/**`：Provider、路由、Session Bootstrap 与 App Shell。

### 协议与业务切片

- `web/src/services/http/**`：transport、错误适配、single-flight refresh、Console/Open client。
- `web/src/services/contracts/**`：OpenAPI 对齐的 DTO；`web/src/entities/**`：UI 使用的领域模型。
- `web/src/features/{auth,knowledge,documents,api-keys,chat}/**`：各自 mapper、Model、ViewModel、View 和测试。
- `web/src/shared/**`：无业务依赖的布局、反馈、响应式和测试工具。

### 部署与验收

- `web/server/server.mjs`：静态文件、SPA fallback、`/health`、同源代理和 Cookie Path 重写。
- `web/Dockerfile`、`web/.dockerignore`：Node 多阶段非 root 镜像。
- `docker-compose.yml`：`web` 服务、端口、环境、依赖和健康检查。
- `scripts/tests/http/web_*.sh`：容器、深链、认证 Cookie、上传和 Chat 回归。

## 关键决策

- 采用管理后台 MVP + 独立 Chat，不构建营销首页。
- 使用 React 19.2、Ant Design 6 和 Vite 8；Node 基线至少 22.12，镜像实施时固定具体 patch 标签。
- TanStack Query 管理服务端状态，不复制到全局 Store；短暂 UI 状态留在 View/ViewModel。
- API Key 独立页在 MVP 中聚合已加载 Knowledge 和其 Key 列表；最大并发 4，单 KB 失败不清空成功数据。
- Chat Key 只保存在页面内存，刷新即清除；一次性 Key 模态框关闭后不可恢复。
- Runtime Server 用 `npm start` 启动，但依赖安装和构建使用 pnpm lock；禁止用 `vite preview`。
- Web 对外只暴露 3000；同源代理保留现有 8080/8081 直接访问兼容性。
- 未批准 Stitch 页面可以先实现结构与行为，最终视觉验收必须以 UI 交接清单的 `已批准` 状态为准。
- 设计调整优先通过 Google Stitch MCP 集中完成；中间 draft 不进入 Git，仓库只保存已批准版本，减少重复 MCP 读取和无意义资产提交。

## 未决问题

无。Stitch 首版已生成但尚未批准，这不阻塞任务 22.1–22.7 按 PRD 完成结构、行为和基础 Ant Design UI；22.8 在设计稿批准后执行，若执行到该任务时仍无批准稿则按工作流记录为外部阻塞，不降低验收标准。

## 风险与回滚

- 认证代理与 Cookie Path 不一致：Runtime Server 组件测试和 Docker 浏览器回归覆盖 register/login/refresh/logout；失败时先回滚 Web 代理提交，不修改后端 Cookie 语义。
- API Key 聚合产生 N+1 压力：并发固定 4、按 Knowledge 分页加载、部分失败可见；规模扩大后另建后端索引 Plan。
- DTO 漂移：mapper 测试使用 OpenAPI fixture，禁止 View 直接消费 DTO；后端变更必须先更新契约。
- 敏感信息泄漏：日志红线、浏览器存储断言和测试快照扫描共同约束。
- Stitch 延迟：行为实现与视觉收敛分任务，22.8 只因外部稿件阻塞，不阻塞前序能力提交。
- Docker 镜像回滚：移除 `web` service 并 revert `web/Dockerfile/server`；无数据库迁移和不可逆数据变化。
- 整体回滚按 22.9→22.1 逆序 revert；前端是新增目录，后端 API 和持久化保持兼容。

## 测试与验证计划

- 静态门禁：`pnpm lint`、`pnpm typecheck`、`pnpm build`。
- 单元测试：mapper、状态规则、错误适配、single-flight refresh、轮询和 API Key 聚合，`pnpm test`。
- 组件测试：React Testing Library + MSW 覆盖各 ViewModel/View 的成功、空、加载和失败状态。
- 浏览器测试：Playwright 覆盖桌面/移动登录、Knowledge、Documents、API Keys 和 Chat；截图与 canvas/pixel 非空检查用于 UI 验收。
- Docker HTTP/浏览器回归：真实 Compose、确定性 LLM Stub，验证深链、Cookie、上传、摄取到 READY 和 Open Query。
- 根目录门禁：`./gradlew spotlessCheck test check`、Plan/约束/OpenAPI 校验，确保 Web 变更不破坏既有后端。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 22.1 | 建立 Web 工程治理、脚手架与应用 Shell | ⏳ 待验收 | a1582767 | — |
| 22.2 | 建立双 API Client、统一错误与认证 Session | ⏳ 待验收 | bafd53aa | — |
| 22.3 | 完成注册、登录与受保护路由 | ⏳ 待验收 | 3657150d | — |
| 22.4 | 完成 Knowledge 管理 | ⏳ 待验收 | d9ca25e4 | — |
| 22.5 | 完成 Document 上传与摄取状态 | ⏳ 待验收 | 3e2c2747, 35256a1f | — |
| 22.6 | 完成 API Key 双视角管理 | ⏳ 待验收 | af064df, f24e598, 45693d0 | — |
| 22.7 | 完成知识检索 Chat | ⏳ 待验收 | 48a4f27 | — |
| 22.8 | 按批准设计稿完成视觉与响应式验收 | ⏳ 待验收 | 9320e13 | — |
| 22.9 | 完成 Node Docker 部署与全链路交接 | ⏳ 待验收 | f215ea8 | — |

整体进度：0 / 9（0%）

## 22.1 建立 Web 工程治理、脚手架与应用 Shell

**目标**：交付可构建、可测试、具有稳定 MVVM 边界的 Web 工程和空页面导航。
**前置任务**：无
**范围**：package/lock、Vite/TS/ESLint/Prettier/Vitest/Playwright、Web 约束、Provider、路由、Shell、404 与测试基础设施。
**非目标**：不调用真实 API，不实现业务页面内容，不创建 Docker 镜像。
**验收标准**：所有命令可运行；`web/AGENTS.md` 与 `CLAUDE.md` 字节一致；路由可达；架构测试阻止 View 依赖 transport 和跨 feature internal import。
**验证方式**：`cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build`；`pnpm exec playwright test tests/e2e/app-shell.spec.ts`。
**涉及文件**：`web/package.json`、lock/config、`web/constraints/**`、`web/src/app/**`、`web/src/shared/**`、`web/src/pages/**`、基础测试。

**Interfaces**：

```ts
export type AppRoute =
  | '/login' | '/register' | '/app/knowledge'
  | `/app/knowledge/${string}` | '/app/api-keys' | '/app/chat';
export function createAppRouter(): Router;
export function AppProviders(props: { children: React.ReactNode }): JSX.Element;
```

**Implementation steps**：

- [ ] 写失败的配置/架构测试，断言 scripts、strict TS、路由集合、View 禁止导入 `services/http`；首次运行因工程不存在失败。
- [ ] 创建 React 19.2 + Vite 8 + Ant Design 6 工程并生成 pnpm lock；所有依赖写入 package.json，不手改 lock。
- [ ] 创建五份 `web/constraints`、字节一致的 AGENTS/CLAUDE 和中文 README，明确 MVVM 与根 Plan 路由。
- [ ] 实现 AppProviders、router、桌面/移动 Shell、占位页面、Error Boundary 与 404；图标只用 `@ant-design/icons`。
- [ ] 运行本任务全部门禁和 Playwright desktop/mobile shell 测试，预期通过且无 console error。
- [ ] 提交：`feat(plan_22/22.1): scaffold governed web console`。

## 22.2 建立双 API Client、统一错误与认证 Session

**目标**：让所有后续 feature 只依赖稳定 Client 与领域错误，不接触 fetch 和原始 Envelope。
**前置任务**：22.1
**范围**：transport、Envelope/Error DTO、Console/Open client、DTO mapper、内存 token、single-flight refresh、Query keys、MSW fixtures。
**非目标**：不实现登录表单和业务页面，不持久化 Access Token。
**验收标准**：并发 401 只 refresh 一次；成功后各请求重放一次；refresh 失败清会话；Open 不触发 Console refresh；错误保留 traceId/retryable/fieldErrors 且不泄密。
**验证方式**：`pnpm test -- src/services`；MSW 并发测试；`pnpm typecheck`。
**涉及文件**：`web/src/services/**`、`web/src/entities/session.ts`、`web/src/test/msw/**`、对应测试。

**Interfaces**：

```ts
export interface ApiError {
  kind: 'validation' | 'authentication' | 'authorization' | 'business' | 'retryable' | 'unknown';
  message: string; traceId?: string; retryable: boolean;
  fieldErrors: ReadonlyArray<{ field: string; message: string }>;
}
export interface SessionStore {
  getAccessToken(): string | null;
  setAccessToken(token: string): void;
  clear(): void;
}
export interface HttpClient {
  request<T>(request: HttpRequest): Promise<T>;
}
export const consoleClient: HttpClient;
export const openClient: HttpClient;
```

**Implementation steps**：

- [ ] 用 MSW 写 Envelope 解包、字段错误、502/503、并发 401、refresh 失败和 Open 隔离的失败测试。
- [ ] 实现纯 `mapApiError`、SessionStore 和可注入 fetch 的 transport；日志只记录 method/path/status/traceId。
- [ ] 实现 Console single-flight refresh 与最多一次 replay；refresh 请求使用 `credentials: 'include'`。
- [ ] 实现 Open client、Query key factory 和测试 fixtures；禁止 Open client 读取 SessionStore。
- [ ] 运行 services 测试、秘密字符串扫描、lint/typecheck，预期全部通过。
- [ ] 提交：`feat(plan_22/22.2): establish web API and session layer`。

## 22.3 完成注册、登录与受保护路由

**目标**：完成浏览器会话建立、刷新恢复和退出闭环。
**前置任务**：22.2
**范围**：Auth DTO mapper、Zod 表单、Auth ViewModel、登录/注册 View、Session Bootstrap、ProtectedRoute、账户菜单和 logout。
**非目标**：不实现找回密码、MFA、资料修改或 Tenant 切换。
**验收标准**：注册和登录成功进入 Knowledge；刷新时先 bootstrap；失败映射到字段/表单；退出清 token/query cache；匿名访问受保护路由返回登录。
**验证方式**：`pnpm test -- src/features/auth src/app`；`pnpm exec playwright test tests/e2e/auth.spec.ts`。
**涉及文件**：`web/src/features/auth/**`、`web/src/app/session/**`、登录/注册页面、账户菜单和测试。

**Interfaces**：

```ts
export interface AuthSession { userId: string; nickname: string; tenantId: string; role: 'OWNER' | 'MEMBER'; }
export interface AuthViewModel {
  status: 'idle' | 'submitting' | 'authenticated' | 'error';
  fieldErrors: Readonly<Record<string, string>>;
  submit(values: { email: string; password: string }): Promise<void>;
}
export function useSessionBootstrap(): { status: 'loading' | 'authenticated' | 'anonymous'; session: AuthSession | null };
```

**Implementation steps**：

- [ ] 写注册/登录/refresh/logout 的 mapper、ViewModel 和路由失败测试，覆盖 400/401/403。
- [ ] 实现 Auth API、Zod schema 和 Session Bootstrap；默认 Tenant 从注册响应建立，登录/refresh 后按 API 契约恢复 Tenant 上下文。
- [ ] 实现 Ant Design 表单、提交状态、字段错误、受保护路由和账户退出，不使用静态 message API。
- [ ] 用 MSW 与 Playwright 验证匿名、成功、错误、刷新恢复和退出，断言 localStorage/sessionStorage 无 Token。
- [ ] 运行 lint/typecheck/test/build，预期通过。
- [ ] 提交：`feat(plan_22/22.3): implement console authentication`。

## 22.4 完成 Knowledge 管理

**目标**：交付 Knowledge 列表、分页、创建、详情和 API Key readiness 轮询。
**前置任务**：22.3
**范围**：Knowledge DTO mapper、Model、Query/Mutation、列表、创建模态框、详情 Overview、空/错/加载状态和 readiness polling。
**非目标**：不实现删除、服务端搜索、文档摘要或虚构统计。
**验收标准**：pageToken 正确推进；创建 201 部分成功可进入详情；`apiKeyReady=false` 显示警告并轮询；离开详情停止轮询。
**验证方式**：`pnpm test -- src/features/knowledge`；Knowledge Playwright desktop/mobile。
**涉及文件**：`web/src/features/knowledge/**`、Knowledge 页面、MSW handlers 和测试。

**Interfaces**：

```ts
export interface KnowledgeBase {
  id: string; tenantId: string; name: string; apiKeyReady: boolean;
  createdAt: string; updatedAt: string;
}
export interface KnowledgePage { items: ReadonlyArray<KnowledgeBase>; nextPageToken: string; }
export function useKnowledgeList(): KnowledgeListViewModel;
export function useKnowledgeDetail(id: string): KnowledgeDetailViewModel;
```

**Implementation steps**：

- [ ] 写 DTO 映射、token 分页、创建 partial success 和轮询停止的失败测试。
- [ ] 实现 Knowledge API/mapper/model 与 query keys，ID 始终作为 string。
- [ ] 实现列表、创建和详情 ViewModel；只在未就绪且页面可见时轮询。
- [ ] 实现 Ant Design desktop table/mobile list、modal、Overview 和所有状态。
- [ ] 运行 feature 测试和两种 viewport Playwright，预期通过且布局不跳动。
- [ ] 提交：`feat(plan_22/22.4): implement knowledge management`。

## 22.5 完成 Document 上传与摄取状态

**目标**：让用户上传受支持文件并观察 PENDING/PROCESSING/READY/FAILED 生命周期。
**前置任务**：22.4
**范围**：Document mapper/model、列表、multipart 上传、客户端预检、状态轮询、失败详情和条件 retry。
**非目标**：不删除、下载、编辑文件，不以客户端校验替代后端结果。
**验收标准**：只接受 `.txt/.md` 且不超过 10 MiB；上传 202 后入列表；活跃状态轮询；只有 `retryable=true` 显示 retry；失败原因安全展示。
**验证方式**：`pnpm test -- src/features/documents`；Playwright 上传 fixture 并轮询 READY/FAILED。
**涉及文件**：`web/src/features/documents/**`、Knowledge Documents tab、`web/tests/fixtures/**` 和测试。

**Interfaces**：

```ts
export type IngestionStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';
export interface DocumentItem {
  id: string; knowledgeBaseId: string; filename: string; sizeBytes: number;
  status: IngestionStatus; attempt: number; retryable: boolean;
  failureMessage: string | null; updatedAt: string | null;
}
export function validateUpload(file: File): { valid: true } | { valid: false; message: string };
export function useDocuments(knowledgeBaseId: string): DocumentsViewModel;
```

**Implementation steps**：

- [ ] 写扩展名/大小边界、DTO null 字段、轮询、retry 可见性和上传错误失败测试。
- [ ] 实现 API/mapper/model、multipart transport 和纯 validateUpload；后端错误优先于客户端通用文案。
- [ ] 实现列表/上传/retry ViewModel，只有页面可见且存在活跃项时轮询。
- [ ] 实现上传区、稳定尺寸状态列表、失败详情和 retry 操作；移动端改结构化列表。
- [ ] 运行 feature/Playwright 测试，确认上传流未被 JSON transport 破坏。
- [ ] 提交：`feat(plan_22/22.5): implement document ingestion UI`。

## 22.6 完成 API Key 双视角管理

**目标**：在 Knowledge 内和独立索引页安全管理 API Key 全生命周期。
**前置任务**：22.4
**范围**：Key mapper/model/actions、Knowledge tab、独立聚合页、一次性秘密模态框、状态操作与 KB backlink。
**非目标**：不显示历史完整 Key，不实现批量操作或后端全局搜索。
**验收标准**：完整 Key 只存在 mutation result 和打开的模态框；关闭后清除；状态动作矩阵正确；聚合并发最大 4；部分 KB 失败仍显示成功数据。
**验证方式**：`pnpm test -- src/features/api-keys`；Playwright 创建/复制确认/禁用/启用/轮换/撤销及存储扫描。
**涉及文件**：`web/src/features/api-keys/**`、Knowledge API Keys tab、独立页面和测试。

**Interfaces**：

```ts
export type ApiKeyStatus = 'ACTIVE' | 'DISABLED' | 'REVOKED';
export interface ApiKeyItem {
  id: string; knowledgeBaseId: string; name: string; keyPrefix: string;
  status: ApiKeyStatus; expiresAt: string | null;
}
export interface CreatedApiKey extends ApiKeyItem { completeKey: string; }
export function allowedApiKeyActions(status: ApiKeyStatus): ReadonlyArray<'disable' | 'enable' | 'rotate' | 'revoke'>;
export function useApiKeyIndex(options?: { concurrency?: 4 }): ApiKeyIndexViewModel;
```

**Implementation steps**：

- [ ] 写状态矩阵、一次性秘密清理、有界聚合和部分失败的失败测试。
- [ ] 实现 API/mapper/model 与 mutations；completeKey 不进入 Query cache。
- [ ] 实现 Knowledge tab 和独立页 ViewModel，使用 worker pool 将并发固定为 4。
- [ ] 实现列表/移动列表、KB backlink、创建/轮换秘密模态框与危险确认。
- [ ] 运行组件/Playwright 和 localStorage/sessionStorage/console 扫描，预期无完整 Key。
- [ ] 提交：`feat(plan_22/22.6): implement API key management`。

## 22.7 完成知识检索 Chat

**目标**：交付输入临时 API Key 即可执行问答并查看来源的独立 Chat。
**前置任务**：22.2
**范围**：Open Query mapper、页面内 Key、消息 Model/ViewModel、composer、答案 sources、无结果、无效 Key、可重试错误。
**非目标**：不保存聊天历史、不选择 KB、不流式输出、不自动重试 LLM Query。
**验收标准**：请求体只有 question；Authorization 使用当前内存 Key；发送中禁重复提交；来源只显示 reference/documentId/excerpt；刷新清 Key 和消息。
**验证方式**：`pnpm test -- src/features/chat`；Chat desktop/mobile Playwright。
**涉及文件**：`web/src/features/chat/**`、Chat 页面、Open MSW handlers 和测试。

**Interfaces**：

```ts
export interface QuerySource { reference: string; documentId: string; excerpt: string; }
export interface ChatMessage {
  id: string; role: 'user' | 'assistant'; content: string;
  sources: ReadonlyArray<QuerySource>; status: 'sending' | 'complete' | 'failed';
}
export function useChat(): ChatViewModel;
```

**Implementation steps**：

- [ ] 写 Open request shape、Key 内存生命周期、消息状态和 401/502/503 映射失败测试。
- [ ] 实现 Open Query API/mapper 和 Chat Model；禁止提交任何 Console Session 字段。
- [ ] 实现 ViewModel 的发送、失败保留、显式重试和 Key 更换清会话命令；不自动重试 Query。
- [ ] 实现消息流、来源区、固定 composer 和移动键盘布局，保证最后消息不被遮挡。
- [ ] 运行测试与 Playwright，刷新后断言输入 Key 和消息均为空。
- [ ] 提交：`feat(plan_22/22.7): implement knowledge chat`。

## 22.8 按批准设计稿完成视觉与响应式验收

**目标**：将 Stitch 已批准稿转为稳定 Token、公共组件和各页面最终视觉。
**前置任务**：22.3、22.4、22.5、22.6、22.7
**范围**：读取 UI handoff、导出快照、Ant Design Token、公共状态组件、桌面/移动适配、键盘与焦点、视觉回归。
**非目标**：不改变业务契约和功能范围，不实现未批准设计探索。
**验收标准**：handoff 中目标页面均为已批准；实现与快照一致；无重叠/溢出/空白画面；键盘流程和可访问名称完整；核心 viewport 截图获批准。
**验证方式**：Playwright `1440x900`、`1024x768`、`390x844` 截图；axe 检查；canvas/pixel 非空和页面 console error 检查。
**涉及文件**：`docs/product/web-console-ui-handoff.md`、`docs/product/ui/**`、`web/src/app/theme/**`、`web/src/shared/ui/**`、各 feature View/CSS 和视觉测试。

**Interfaces**：

```ts
export const cragTheme: ThemeConfig;
export type AsyncViewState = 'loading' | 'empty' | 'error' | 'ready';
export function AsyncState(props: AsyncStateProps): JSX.Element;
```

**Implementation steps**：

- [ ] 校验 handoff 的 Stitch 链接、版本、批准状态和仓库快照；缺批准稿时记录外部阻塞，不自行宣称视觉完成。
- [ ] 按 handoff 集中读取一次 Stitch 项目/目标 Screen，通过 MCP 完成功能契约修正、缺失状态和响应式调整；每轮调整写入 MCP 调整记录。
- [ ] 用户确认定稿后一次性同步最终截图、必要参考 HTML 和 DESIGN.md 到版本目录，并将目标页面标记为已批准。
- [ ] 写目标 viewport 的失败截图和 axe 测试，固定页面数据与动画。
- [ ] 实现 ThemeConfig、稳定尺寸公共组件和各页面视觉，禁止依赖 Ant Design 内部 DOM selector。
- [ ] 逐页验证长文本、空态、加载、错误、modal/drawer、键盘和触控目标；修复重叠与布局跳动。
- [ ] 更新 handoff 的实现映射和截图版本，运行全量 Playwright 视觉/可访问性测试。
- [ ] 提交：`feat(plan_22/22.8): apply approved web console design`。

## 22.9 完成 Node Docker 部署与全链路交接

**目标**：让完整产品通过一个 Compose 命令启动，并形成可独立验收的真实证据。
**前置任务**：22.7
**范围**：Runtime Server、Dockerfile、Compose web、Origin/Cookie 配置、健康检查、Docker 脚本、README/约束/validator、全量测试和 Plan 交接。
**非目标**：不使用 Nginx，不删除 8080/8081 宿主机兼容端口，不执行最终独立验收。
**验收标准**：`npm start` 非 Vite preview；web 非 root；深链 200；代理/Cookie/上传/Chat 工作；Compose 全健康；文档与服务索引一致；全部任务有实现 hash 后进入 verifying。
**验证方式**：下列命令全部真实运行，必跑项不得 skip。
**涉及文件**：`web/server/**`、`web/Dockerfile`、`web/.dockerignore`、`docker-compose.yml`、`.env.example`、Docker/test 约束、README、validators、`scripts/tests/http/web_*.sh`、Plan/index。

**Interfaces**：

```text
GET /health                         -> 200 {"status":"UP"}
/console-api/<path>                 -> console-api:8080/<path>
/open-api/<path>                    -> open-api:8081/<path>
Set-Cookie Path=/api/v1/auth        -> Path=/console-api/api/v1/auth
WEB_PORT=3000
CONSOLE_API_ORIGIN=http://console-api:8080
OPEN_API_ORIGIN=http://open-api:8081
```

**Implementation steps**：

- [ ] 写 Runtime Server 测试，覆盖 health、静态文件、SPA fallback、双代理、502/503、上传流和 Cookie Path 重写；首次因 server 不存在失败。
- [ ] 实现可注入 upstream 的 Node Server；日志脱敏；`npm start` 运行 server.mjs，SIGTERM 优雅停止。
- [ ] 创建固定 Node 22 Alpine 多阶段 Dockerfile和 ignore；运行镜像只含 dist/server/生产依赖并使用 `node` 用户。
- [ ] Compose 新增 web、3000 映射、健康依赖与 Console allowed-origin/cookie-secure Demo 配置；同步 Docker 约束和机械校验测试。
- [ ] 写并运行 `web_container.sh`、`web_auth.sh`、`web_upload_chat.sh`，使用唯一 runId、确定性 LLM Stub、不清数据库/Volume。
- [ ] 运行 `cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm e2e`。
- [ ] 运行 `./gradlew spotlessCheck test check`、`python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py`、`python3 scripts/validate_openapi.py`。
- [ ] 更新 README、docs 索引、验收记录和真实实现 hash；全部任务待验收后将 Plan/index 转为 verifying。
- [ ] 提交：`feat(plan_22/22.9): ship web console Docker deployment`，随后独立提交交接记录。

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.1 实现自测） | `cd web && pnpm lint` | ✅ 通过 | eslint . 0 错误 |
| 2026-07-02 | 同上 | `cd web && pnpm typecheck` | ✅ 通过 | tsc -b --noEmit 干净；strict TS 生效 |
| 2026-07-02 | 同上 | `cd web && pnpm test` | ✅ 通过 | 4 文件 13 测试，含架构门禁（scripts/strict TS/路由集合/View 禁止 services/http/跨 feature 内部导入/AGENTS 与 CLAUDE 字节一致） |
| 2026-07-02 | 同上 | `cd web && pnpm build` | ✅ 通过 | dist 产物生成；非阻塞 chunk 体积警告，业务页拆分属后续任务 |
| 2026-07-02 | 同上 | `cd web && pnpm exec playwright test tests/e2e/app-shell.spec.ts` | ✅ 通过 | 24/24，桌面 1280×720 与移动 390×844 各路由无 console error，404 与动态详情可达 |
| 2026-07-02 | 同上 | `git show --stat a1582767` | ✅ 通过 | 45 文件全部位于 `web/`；`plan_14` 工作区变更未被纳入 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.2 实现自测） | `cd web && pnpm test -- src/services` | ✅ 通过 | 9 文件 61 测试；含并发 401 单飞 refresh、最多一次 replay、refresh 失败清会话、Open 隔离、Envelope 解包、字段错误、502/503 |
| 2026-07-02 | 同上 | `cd web && pnpm typecheck` / `pnpm lint` | ✅ 通过 | strict TS 干净；eslint 0 错误 |
| 2026-07-02 | 同上 | `cd web && pnpm test`（全量） | ✅ 通过 | 22.1 的 13 项与 22.2 新增测试共存，无回归 |
| 2026-07-02 | 同上 | 秘密字符串扫描 | ✅ 通过 | 源码与测试快照无真实 JWT / `crag_…` Key / 密码字面量，fixture 使用 `<PLACEHOLDER_*>` |
| 2026-07-02 | 同上 | `pnpm format:check` | ⚠️ 未通过（非门禁） | 8 个 22.1 期文件未格式化（config/test/README），22.2 自身文件干净；本 Plan 静态门禁与各任务验证方式均未将 prettier 列为门禁，记为残留风险，待后续统一清理 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.3 实现自测） | `cd web && pnpm test -- src/features/auth src/app` | ✅ 通过 | 含 mapper/Zod/ViewModel/ProtectedRoute/bootstrap，覆盖 400/401/403；全量 16 文件 119 测试无回归（含 22.1 架构门禁） |
| 2026-07-02 | 同上 | `cd web && pnpm exec playwright test tests/e2e/auth.spec.ts` | ✅ 通过 | 10/10，桌面+移动：匿名重定向、登录成功进入 Knowledge、失败表单错误、刷新恢复、退出清会话；断言 localStorage/sessionStorage 无 Token |
| 2026-07-02 | 同上 | `cd web && pnpm typecheck` / `pnpm lint` / `pnpm build` | ✅ 通过 | strict TS 干净；eslint 0 错误；build 产物生成（非阻塞 chunk 体积警告） |
| 2026-07-02 | 同上 | Ant Design 6 `Alert.message` 弃用警告 | ⚠️ 非阻断 | 失败登录路径触发 `message→title` 弃用 console 警告；不影响 auth.spec 与 app-shell.spec 的断言，留待 22.8 视觉任务迁移 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.4 实现自测） | `cd web && pnpm test -- src/features/knowledge` + `app/knowledge` | ✅ 通过 | mapper（ID 始终为 string）、pageToken 分页、创建 partial-success 进入详情、就绪轮询启动/卸载停止；新增 33 项测试 |
| 2026-07-02 | 同上 | `cd web && pnpm exec playwright test tests/e2e/knowledge.spec.ts` | ✅ 通过 | 10/10，桌面+移动：列表/分页、创建 partial-success 跳详情、就绪轮询警告消失、返回、移动卡片无横向滚动 |
| 2026-07-02 | 同上 | `cd web && pnpm typecheck` / `pnpm lint` / `pnpm test`（全量 152） | ✅ 通过 | strict TS 干净；eslint 0 错误；无回归；架构 import-boundary 门禁通过（features/knowledge 无 services/http 真实导入） |
| 2026-07-02 | 同上 | Ant Design 6 弃用警告扩展 | ⚠️ 非阻断 | 除 `Alert.message→title` 外新增 `Space.direction→orientation`；不影响 Playwright 断言，留待 22.8 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.5 实现自测） | `cd web && pnpm test -- src/features/documents` + `app/documents` | ✅ 通过 | validateUpload 边界（.txt/.md、≤10MiB）、DTO null 字段、活跃态轮询启停、retry 可见性矩阵、上传 202→list、413/415/409 服务端消息优先 |
| 2026-07-02 | 同上 | `cd web && pnpm exec playwright test tests/e2e/documents.spec.ts` | ✅ 通过（经修复） | 首次执行 desktop retry 收敛用例失败：retry 仅 invalidate、未乐观置 PENDING，mock list 读 state.status 致快速链路在 state 翻 READY 前完成，缓存停 FAILED、轮询不启动；fix `35256a1f` 双层修复（retry onSuccess 乐观置 PENDING + retry handler 置 state PENDING），6/6 通过，`--repeat-each=3` 共 18/18 稳定 |
| 2026-07-02 | 同上 | `cd web && pnpm typecheck` / `pnpm lint` / `pnpm test`（全量 194） | ✅ 通过 | strict TS 干净；eslint 0 错误；multipart 经 transport.form 走 FormData，未被 JSON 破坏；架构门禁不受影响 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.6 实现自测） | `cd web && pnpm test -- src/features/api-keys` + `app/api-keys` | ✅ 通过 | 状态动作矩阵、一次性秘密关闭即清、completeKey 不入 Query cache（缓存扫描断言）、worker pool 并发上限 4、部分 KB 失败仍展示成功数据 |
| 2026-07-02 | 同上 | `cd web && pnpm exec playwright test tests/e2e/api-keys.spec.ts` | ✅ 通过（经两轮修复） | 主 Agent 独立复核发现 mobile e2e 一致失败（非 flaky）：① 视图同时渲染桌面 Table 与移动列表但缺 CSS 文件，移动端宽表溢出致按钮被遮挡（fix f24e598：改 Grid.useBreakpoint 条件渲染 + 补 styles.css）；② Ant Design 模态动画在窄屏拦截 modal→modal 转场的确认点击（fix 45693d0：e2e 注入 `transition/animation:none` 全局禁用动画并移除 force 变通）。最终 `--repeat-each=5` 共 10/10（桌面+移动各 5/5）稳定 |
| 2026-07-02 | 同上 | `cd web && pnpm typecheck` / `pnpm lint` / `pnpm test`（全量 247） | ✅ 通过 | strict TS 干净；eslint 0 错误；架构门禁不受影响 |
| 2026-07-02 | 同上 | Ant Design 6 弃用警告扩展 | ⚠️ 非阻断 | 新增 `Input.addonAfter→Space.Compact`；连同 `Alert.message→title`、`Space.direction→orientation`，留待 22.8 视觉任务统一迁移 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.7 实现自测） | `cd web && pnpm test -- src/features/chat` + `app/chat` | ✅ 通过 | 请求体仅 `{question}`（ViewModel 与 e2e 双重断言，禁带 tenantId/knowledgeBaseId）、API Key 仅内存（Query cache 与 storage 双扫描无完整 Key）、消息状态 sending/complete/failed、401/502/503 错误映射、发送中禁重复提交、显式重试无自动重试 |
| 2026-07-02 | 同上 | `cd web && pnpm exec playwright test tests/e2e/chat.spec.ts --repeat-each=3` | ✅ 通过 | 42/42（14 测试 × 3，桌面+移动）：初始空态、成功查询（body+auth 断言）、禁重复提交、失败保留+重试、无效 Key 401、无结果、clear 清 Key 与消息、刷新清状态、storage 无完整 Key |
| 2026-07-02 | 同上 | 收尾全门禁 | ✅ 通过 | `pnpm lint`/`pnpm typecheck` 干净；`pnpm test` 全量 276 通过；`pnpm build` 产物生成（非阻塞 chunk 体积警告）；**全量 Playwright 66/66**（app-shell+auth+knowledge+documents+api-keys+chat，桌面+移动）无回归 |
| 2026-07-02 | macOS, Node v26.3.1, pnpm 10.32.1（22.8 实现自测） | `cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` | ✅ 通过 | ESLint、strict TS、37 文件 281 项 Vitest、生产构建通过；构建保留既有非阻塞 chunk 体积警告 |
| 2026-07-02 | 同上 | `cd web && pnpm e2e` | ✅ 通过 | 74/74；含 1440×900、1024×768、390×844 截图，WCAG 2 A/AA axe、页面/像素非空、console/page error、横向溢出、Drawer 焦点恢复及 Modal 44px 触控目标检查 |
| 2026-07-02 | 同上 | Ant Design 6 弃用扫描与相关回归 | ✅ 通过 | 完成 `Alert.message→title`、`Space.direction→orientation`、`Input.addonAfter→Space.Compact`，并清理全量回归发现的 `Modal.maskClosable` 警告；SaveKeyModal 5/5 与 typecheck 通过 |
| 2026-07-02 | 同上 | `pnpm format:check` / 22.8 变更文件精确 Prettier 检查 | ⚠️ 基线残留 / ✅ 通过 | 全仓仍含前序任务已记录的格式化存量；22.8 的 19 个实现/测试/文档相关文件精确检查全部通过，未扩散格式化 |
| 2026-07-02 | 同上 | `git show --stat 9320e13`、diff/秘密扫描 | ✅ 通过 | 19 文件均属于 22.8；无密钥、Token 或私钥；未纳入工作区既有 `plan_14` 修改 |
| 2026-07-02 | macOS, Docker 29.5.2；Node v26.3.1（22.9 实现自测） | `cd web && pnpm test -- tests/server`（Runtime Server 行为） | ✅ 通过 | 12 项：/health、静态、SPA 回退、404、目录穿越、/console-api 代理前缀剥离、Cookie Path=/api/v1/auth→/console-api/api/v1/auth 重写、256KiB 流式上传字节完整、503 passthrough、/open-api 独立上游、502 不可达、SIGTERM `server.close()` |
| 2026-07-02 | 同上 | `cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` | ✅ 通过 | ESLint/strict TS/38 文件 293 项 Vitest/生产构建通过；构建保留既有非阻塞 chunk 体积警告；server.mjs 用 `npm install -g pnpm@10.32.1`（容器内 corepack 无法验证 pnpm 签名） |
| 2026-07-02 | 同上 | `cd web && pnpm e2e` | ✅ 通过（含 drive-by） | 74/74；首次执行发现 3 项 documents mobile e2e 失败（22.5 引入的 `.first()` 定位 bug，desktop/mobile 双渲染下移动取到隐藏桌面单元格），独立提交 `a58747c` 修复（新增 `visibleText` 用 `:visible` 容器定位）后 74/74；documents `--repeat-each=3` 共 18/18 稳定 |
| 2026-07-02 | 完整 Compose（9 服务健康，LLM Stub） | `bash scripts/tests/http/web_container_test.sh` | ✅ 通过 | 8/8：/health UP、/ 返回 SPA index.html、深链 SPA 回退、缺失资源 404、/console-api 与 /open-api 代理穿透 readiness、容器非 root（uid=1000）、PID 1 = `node server/server.mjs`（非 vite preview） |
| 2026-07-02 | 同上 | `bash scripts/tests/http/web_auth_test.sh` | ✅ 通过（经测试脚本修正） | 7/7：register/login/refresh/logout/me 经代理；Set-Cookie `Path=/console-api/api/v1/auth` 重写在原始响应头与 cookie jar 双重验证；refresh 同源 Cookie 回送成功；缺 Origin 403；无 Bearer 401；校验失败 400。首轮测试误用 `Path=` grep cookie jar 格式，改为 `-D` 捕获原始响应头断言 |
| 2026-07-02 | 同上 | `bash scripts/tests/http/web_upload_chat_test.sh` | ✅ 通过 | 经代理全链路：register→create KB（等 apiKeyReady）→create API Key→upload .txt 202→等 ingestion READY→Open Query 200 answer；body.knowledgeBaseId 被忽略；缺 Bearer 401；question 超长 400 |
| 2026-07-02 | 同上 | `docker compose ps`（全栈健康） | ✅ 通过 | redis/db/sidecar/access/knowledge/rag/console-api/open-api/web 共 9 服务全部 healthy；web 健康检查 = Node 内置 http 调 /health |
| 2026-07-02 | macOS | `./gradlew spotlessCheck test check`、`python3 scripts/validate_constraints.py`、`python3 scripts/validate_plans.py`、`python3 scripts/validate_openapi.py` | ✅ 通过 | Gradle BUILD SUCCESSFUL；validate_constraints 0 error；validate_plans 0 error（24 warning 均为历史 v2 plan）；validate_openapi 0 error；Web 变更未触及 Java 代码 |
| 2026-07-02 | 同上 | `git show --stat f215ea8`、`git show --stat a58747c`、秘密扫描 | ✅ 通过 | feat 15 文件均为 22.9 交付物；documents 修复 1 文件；无密钥/Token/私钥；未纳入工作区既有 `plan_14` 修改 |

## 阻塞记录

无。发生阻塞时记录原因、当前进度、解除条件、解除方、下一步与日期。

## 废弃任务记录

无。任务废弃时记录原因、日期及替代任务或决策。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-07-02 | 创建 plan_22 并进入 ready | Web 产品、架构、UI 交接和 Node Docker 设计已获用户确认 | 新增 9 个有序任务，登记执行队列 |
| 2026-07-02 | 开始执行：ready → in_progress；22.1 实现提交 a1582767 并转待验收 | 22.1 由独立 SubAgent 完成 Web 工程治理、脚手架与应用 Shell | 执行队列进度更新；自测全门禁通过 |
| 2026-07-02 | 22.2 实现提交 bafd53aa 并转待验收 | 22.2 由独立 SubAgent 完成双 API Client、统一错误与认证 Session（内存 token、single-flight refresh、Open 隔离、MSW 基建） | 自测通过；记录 prettier 非门禁残留风险 |
| 2026-07-02 | 22.3 实现提交 3657150d 并转待验收 | 22.3 由独立 SubAgent 完成注册/登录/受保护路由/账户退出；编排层置于 `app/session`，修正 `test/setup.ts` 的 afterEach 来源，consoleClient 增加 skipRefreshPaths | 自测通过；记录 AntD Alert 弃用警告待 22.8 处理 |
| 2026-07-02 | 22.4 实现提交 d9ca25e4 并转待验收 | 22.4 由独立 SubAgent 完成 Knowledge 列表/分页/创建/详情/就绪轮询；镜像 22.3 分层（model+View 在 features/knowledge，编排+ViewModel 在 app/knowledge） | 自测通过；Space 弃用警告并入 22.8 |
| 2026-07-02 | 22.5 实现提交 3e2c2747、修复 35256a1f 并转待验收 | 22.5 由独立 SubAgent 完成 Document 上传/摄取/条件 retry；主 Agent 独立复核发现 retry→READY e2e 竞态失败，另起独立 SubAgent 用 systematic-debugging 定位并修复（retry onSuccess 乐观置 PENDING + mock state 真实化） | 自测通过；记录 retry 收敛竞态修复证据 |
| 2026-07-02 | 22.6 实现提交 af064df、修复 f24e598、45693d0 并转待验收 | 22.6 由独立 SubAgent 完成 API Key 双视角管理（Knowledge tab + 独立聚合页、一次性秘密模态框、worker pool 并发 4、部分失败展示、completeKey 不持久化）；主 Agent 独立复核发现 mobile e2e 一致失败，先后两轮独立 SubAgent 修复（条件渲染补 CSS + e2e 禁用模态动画） | 自测通过；记录 mobile e2e 两轮修复与 `--repeat-each=5` 稳定证据 |
| 2026-07-02 | 22.7 实现提交 48a4f27 并转待验收；22.1–22.7 全部转待验收 | 22.7 由独立 SubAgent 完成知识检索 Chat（Open Query、内存 API Key、消息 Model/ViewModel、composer、来源区、失败显式重试）；e2e 吸取 22.6 教训预先禁用模态动画 | 22.1–22.7 实现闭环完成；22.8、22.9 仍待开始，Plan 保持 in_progress；下一 Session 从 22.8 开始 |
| 2026-07-02 | 22.8 实现提交 9320e13 并转待验收 | 按批准的 Local baseline `stitch-v4` 落地 ThemeConfig、公共 AsyncState、统一页面视觉与三视口响应式/axe/截图验收，迁移 Ant Design 6 已知弃用项 | 22.1–22.8 待验收；22.9 仍待开始，Plan 保持 in_progress；下一 Session 从 22.9 开始 |
| 2026-07-02 | 22.9 实现提交 f215ea8、drive-by 修复 a58747c 并转待验收；Plan → verifying | 22.9 完成 Node Runtime Server（/health、静态、SPA 回退、/console-api 与 /open-api 同源代理、Cookie Path 重写、SIGTERM）、Node 多阶段非 root Dockerfile、Compose `web` 服务（3000，console-api allowed-origins 纳入 3000）、docker-structure 约束 + validator 同步、三个 Docker HTTP 回归脚本；drive-by 修复 22.5 documents e2e mobile `.first()` 定位 bug 以通过 22.9 `pnpm e2e` 门禁 | 22.1–22.9 全部待验收；Plan 转为 `verifying` 待独立验收；执行队列清空、验收队列登记 plan_22 |
