# CRAG-Demo — 项目索引

## 项目概述

基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。技术栈：Java 21 + Spring Boot + Gradle + Docker + pgvector。

## 核心规范

1. **Plan 工作流约束**：统一维护在 `constraints/plan-workflow.md`；本文件只保留路由，不重复展开细则。
2. **代码风格约束**：统一维护在 `constraints/code-style.md`；本文件只保留路由，不重复展开细则。
3. **包结构约束**：统一维护在 `constraints/package-structure.md`；本文件只保留路由，不重复展开索引。
4. **Docker 部署结构约束**：统一维护在 `constraints/docker-structure.md`；本文件只保留路由，不重复展开索引。
5. **README.md** — 使用中文维护，随项目目标持续更新。
6. **开源协议**：MIT。

## 包结构索引

详见 `constraints/package-structure.md`。

## Docker 部署结构

详见 `constraints/docker-structure.md`。

## 当前状态

- [x] 项目规划初始化
- [x] plan_main 细化 — 混合检索流水线（BM25 + pgvector + RRF）
- [ ] plan_A 执行计划创建（最小 Demo：core 全链路）
- [ ] 项目脚手架搭建（plan_A 子任务）
- [ ] 核心功能实现（plan_A 子任务）
- [ ] LLM 接入（plan_B）
- [ ] Docker 化部署（plan_C）

## 对话约定

- 涉及计划创建、命名、执行和进度更新时，必须遵守 `constraints/plan-workflow.md`。
- 涉及 Java 代码时，必须遵守 `constraints/code-style.md`。
- 涉及 Java 包结构调整时，必须同步更新 `constraints/package-structure.md`。
- 涉及 Docker 部署结构调整时，必须同步更新 `constraints/docker-structure.md`。
