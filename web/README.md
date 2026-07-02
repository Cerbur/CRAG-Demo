# CRAG Web Console

CRAG-Demo 的 React + TypeScript 前端工程，位于仓库根目录 `web/`。

## 环境要求

- Node ≥ 22.12
- pnpm 10.x

## 安装

```bash
cd web
pnpm install
```

依赖锁定在 `pnpm-lock.yaml`；新增或升级依赖时把声明写入 `package.json` 后用 `pnpm install` 重新生成 lock，不要手改 lock。

## 常用命令

| 命令 | 说明 |
| --- | --- |
| `pnpm dev` | 启动 Vite 开发服务器（默认 5173）。 |
| `pnpm build` | 生产构建（`tsc -b && vite build`），产物输出到 `dist/`。 |
| `pnpm start` | 启动 Node 运行时服务器（22.9 实现完整代理与静态托管）。 |
| `pnpm lint` | ESLint flat config 全量检查。 |
| `pnpm typecheck` | TypeScript 严格类型检查。 |
| `pnpm test` | Vitest 单元 + 组件测试。 |
| `pnpm e2e` | Playwright E2E（桌面 + 移动 chromium）。 |
| `pnpm format` / `pnpm format:check` | Prettier 格式化 / 检查。 |

## 工程结构

```text
web/
├── src/
│   ├── app/        # Provider、路由、主题、Shell、Error Boundary
│   ├── pages/      # 页面 View（占位，22.3+ 替换为真实页面）
│   ├── features/   # 业务能力切片（auth/knowledge/documents/api-keys/chat）
│   ├── entities/   # 跨 feature 共享领域类型
│   ├── services/   # API Client 与会话（22.2 实现）
│   ├── shared/     # 无业务依赖的 UI 与工具
│   └── test/       # 测试基础设施
├── tests/
│   ├── architecture.*.test.ts   # MVVM 边界与配置门禁
│   └── e2e/                     # Playwright
├── constraints/    # 架构、代码、UI、API、测试规则
├── AGENTS.md / CLAUDE.md / README.md
└── package.json / tsconfig*.json / vite.config.ts / vitest.config.ts / playwright.config.ts / eslint.config.js / .prettierrc.json
```

## 约束

- 架构分层与依赖方向：`constraints/architecture.md`
- 代码风格：`constraints/code-style.md`
- UI 与响应式：`constraints/ui-style.md`
- API Client 与认证：`constraints/api-client.md`
- 测试工作流：`constraints/test-workflow.md`
- Plan 状态与提交：根目录 `constraints/plan-workflow.md`

`web/` 不维护独立 Plan 目录；所有计划仍由根目录 `plan/` 统一管理。

## Playwright 首次运行

首次执行 `pnpm e2e` 前需要安装浏览器：

```bash
pnpm exec playwright install --with-deps chromium
```

或仅安装 chromium（CI 环境无需系统依赖时）：

```bash
pnpm exec playwright install chromium
```
