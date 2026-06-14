# CRAG-Demo — 项目索引

## 项目概述

基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。技术栈：Java 21 + Spring Boot + Gradle + Docker + pgvector。

## 核心规范

1. **Plan 工作流约束**：统一维护在 `constraints/plan-workflow.md`；本文件只保留路由，不重复展开细则。
2. **代码风格约束**：统一维护在 `constraints/code-style.md`；本文件只保留路由，不重复展开细则。
3. **包结构约束**：统一维护在 `constraints/package-structure.md`；本文件只保留路由，不重复展开索引。
4. **Docker 部署结构约束**：统一维护在 `constraints/docker-structure.md`；本文件只保留路由，不重复展开索引。
5. **测试工作流约束**：统一维护在 `constraints/test-workflow.md`；本文件只保留路由，不重复展开细则。
6. **README.md** — 使用中文维护，随项目目标持续更新。
7. **开源协议**：MIT。

## 包结构索引

详见 `constraints/package-structure.md`。

## Docker 部署结构

详见 `constraints/docker-structure.md`。

## 测试工作流

详见 `constraints/test-workflow.md`。

## 当前状态

- [x] 项目规划初始化
- [x] plan_main 收敛 — 只保留总体方向与阶段边界
- [x] plan/index 建立 — 统一维护计划主要功能与完成状态
- [x] plan_1 完成 — 项目脚手架 + 基础设施 + 分包结构
- [x] plan_2 完成 — AdminRag 写入链路 + Dense Embedding 异步处理
- [x] plan_3 完成 — 项目介绍文档 + 架构 SVG + 协作约束整理
- [ ] plan_4 待创建 — Sparse + Dense 查询、RRF、Rerank、UserQuery、LLM 全链路

## 对话约定

- 涉及计划创建、命名、执行和进度更新时，必须遵守 `constraints/plan-workflow.md`。
- 查询计划状态时优先查看 `plan/index/README.md`；不要把执行计划索引写回 `plan/plan_main.md`。
- 涉及 Java 代码时，必须遵守 `constraints/code-style.md`。
- 涉及 Java 包结构调整时，必须同步更新 `constraints/package-structure.md`。
- 涉及 Docker 部署结构调整时，必须同步更新 `constraints/docker-structure.md`。
- 涉及测试执行方式时，必须遵守 `constraints/test-workflow.md`。
