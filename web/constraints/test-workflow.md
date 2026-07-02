# Web 测试工作流约束

> 测试层级、命令与质量门禁。

## 1. 命令

| 命令 | 用途 |
| --- | --- |
| `pnpm lint` | ESLint flat config 全量检查。 |
| `pnpm typecheck` | `tsc -b --noEmit`。 |
| `pnpm test` | Vitest 单元 + 组件测试（jsdom）。 |
| `pnpm test:watch` | Vitest watch 模式。 |
| `pnpm e2e` | Playwright E2E（桌面 + 移动 chromium）。 |
| `pnpm build` | 生产构建（`tsc -b && vite build`）。 |
| `pnpm format:check` | Prettier 格式检查。 |

## 2. 测试层级

### 2.1 单元测试（Vitest）

- 覆盖：mapper、状态规则、错误适配、single-flight refresh、轮询停止、API Key 聚合并发与部分失败、纯函数 `validateUpload`/`allowedApiKeyActions` 等。
- 纯逻辑测试不依赖 React；放置于对应 feature 的 `tests/` 或顶层 `tests/`。

### 2.2 组件测试（React Testing Library + MSW）

- 覆盖每个 ViewModel/View 的：成功、空、加载、失败四种状态。
- MSW handlers 集中维护在 `src/test/msw/**`；fixture 不得包含真实 Token/Key。
- 组件测试不发起真实网络请求；`services/http` 的 transport 必须可注入 fetch。

### 2.3 E2E（Playwright）

- 桌面（1280×720）与移动（390×844）至少各覆盖核心流程。
- 断言浏览器 console 无 uncaught error；断言 `localStorage`/`sessionStorage` 不含完整 Token/Key。
- 视觉回归（截图、canvas 非空、axe）在 22.8 落地。

## 3. 架构测试

`tests/architecture.*` 是 MVVM 边界的强制门禁，必须随 `pnpm test` 通过：

- 配置：`package.json` 脚本齐备、`tsconfig.app.json` `strict: true`、`pnpm-lock.yaml` 提交。
- 路由：`APP_ROUTES` 与 `AppRoute` 类型一致。
- 边界：View/pages 不导入 `services/http`；feature 不互相导入内部。

## 4. 秘密扫描

涉及 Token/Key 的测试必须断言：

- 测试快照不含完整 secret（使用固定前缀 `sk_test_***` 或占位）。
- 组件卸载或刷新后内存字段清空。

## 5. 不依赖真实后端

22.x 的测试默认使用 MSW；联调阶段才连接本地 Console API `8080` 与 Open API `8081`。Docker 全链路回归在 22.9 完成。
