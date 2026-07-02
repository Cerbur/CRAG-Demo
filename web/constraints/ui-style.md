# Web UI 约束

> 视觉、组件、状态与响应式规则。

## 1. 组件库

- 只允许使用 Ant Design 6 与 `@ant-design/icons` 6。
- 禁止引入 Ant Design Pro、Material UI、Chakra、Tailwind CDN 或其他 UI 框架。
- 图标只使用 `@ant-design/icons`；禁止内联 SVG 体系化图标集。

## 2. 状态色

- processing / 加载中：blue。
- ready / 成功：green。
- warning：orange。
- failure / danger：red。
- disabled / 不可用：grey。

主题 Token 统一在 `src/app/theme.ts` 维护，禁止在组件中硬编码上述语义色值。

## 3. 响应式

- 桌面（≥768px）：固定左侧导航 + 顶部工具栏。
- 移动（<768px）：抽屉/汉堡导航，核心流程改为结构化列表，禁止横向滚动作为核心交互。
- 使用 Ant Design `Grid.useBreakpoint()` 切换布局；断点以 `md` (768px) 为分界。

## 4. 状态覆盖

每个数据驱动页面必须覆盖：

- 成功（ready）。
- 空态（empty）。
- 加载中（loading）。
- 主要失败（error），保留可重试动作但禁止泄漏 Token/Key/traceId 以外的内部细节。

`shared/ui` 中将提供统一的 `AsyncState` 容器（22.8 落地视觉版本）；在此之前页面自行用 Ant Design `Spin`、`Empty`、`Result` 实现。

## 5. 可访问性

- 交互元素必须可键盘操作并具备稳定 `aria-label`。
- 模态、抽屉关闭需可恢复焦点。
- 危险操作（撤销、轮换 Key 等）必须有显式确认。
