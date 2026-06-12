# Plan_3 — 项目介绍文档 + 架构 SVG

> 创建时间：2026-06-12
> 任务来源：为后续项目介绍内容建立文档入口，并参考现有架构图风格新增可维护 SVG，插入 README。

---

## 范围说明

本计划只处理项目文档与视觉资产，不改动 Java / Gradle / Docker 运行逻辑。

交付内容：

1. 新建 `doc/project_intro.md`，作为后续项目介绍内容的承载文档。
2. 新建 `doc/assets/crag-demo-architecture.svg`，参考给定图片风格绘制 CRAG-Demo 全链路架构 SVG。
3. 更新 `README.md`，在项目简介后插入架构图入口。
4. 更新 `plan/plan_main.md`，记录文档资产建设进度。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 3.1 | 新建项目介绍文档骨架 | ✅ 完成 | — | 2026-06-12 |
| 3.2 | 绘制架构 SVG 资产 | ✅ 完成 | — | 2026-06-12 |
| 3.3 | README 插入 SVG 架构图 | ✅ 完成 | — | 2026-06-12 |
| 3.4 | plan_main 同步进度 | ✅ 完成 | — | 2026-06-12 |
| 3.5 | 文档与 SVG 基础校验 | ✅ 完成 | — | 2026-06-12 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**5 / 5（100%）**

---

## 完成记录

- 新增 `doc/project_intro.md`：项目介绍文档入口，预留后续介绍、演示、表结构、Roadmap 内容。
- 新增 `doc/assets/crag-demo-architecture.svg`：参考输入图风格绘制全链路架构图。
- 更新 `README.md`：在项目简介后插入架构图，并链接项目介绍文档。
- 更新 `plan/plan_main.md`：同步 `plan_3` 实际范围，并将原查询链路占位顺延为 `plan_4`。
- 校验：`xmllint --noout doc/assets/crag-demo-architecture.svg` 通过。

---

## 设计约束

- README 继续使用中文维护。
- SVG 风格参考输入图：深蓝标题、蓝色主线、青蓝辅助模块、虚线未来层、表格化字段、状态说明与数据流箭头。
- SVG 使用相对路径插入 README，便于 GitHub / 本地 Markdown 直接渲染。
- `doc/project_intro.md` 只放稳定的项目介绍骨架，后续详细内容可继续追加。
