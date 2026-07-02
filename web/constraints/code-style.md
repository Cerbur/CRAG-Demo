# Web 代码风格约束

> TypeScript、命名、不可变性与函数式风格。

## 1. TypeScript

- `tsconfig.app.json` 启用 `"strict": true`；不得关闭 `strict`、`noUnusedLocals`、`noUncheckedIndexedAccess`、`exactOptionalPropertyTypes`、`noImplicitReturns`。
- 禁止 `any`（包括 `as any`）。需要逃逸时使用 `unknown` 配合类型守卫或局部精确断言并加注释。
- 使用 `type` 导入语法 `import type { ... }` 区分值与类型（`@typescript-eslint/consistent-type-imports` 强制）。

## 2. 命名

- 组件、类型：`PascalCase`。
- 函数、变量、hook：`camelCase`。
- 常量集合（路由、枚举映射）：`CONSTANT_CASE` 或 `camelCase`，按可读性选择。
- hook 必须以 `use` 开头，且遵守 React hooks 规则（`eslint-plugin-react-hooks`）。

## 3. 不可变性与函数式

- 函数式组件为主；不引入 class 组件（Error Boundary 除外，因为 React 尚未提供 hook 形态）。
- 数据集合默认 `ReadonlyArray`；状态更新返回新对象，禁止原地 mutate。
- 共享映射函数保持纯函数：无副作用、不抛业务异常、不读全局状态。

## 4. 格式化

- Prettier 配置见 `.prettierrc.json`；提交前运行 `pnpm format:check`。
- ESLint flat config 见 `eslint.config.js`；架构规则由其中 `no-restricted-syntax` 与 `tests/architecture.*` 共同强制。
