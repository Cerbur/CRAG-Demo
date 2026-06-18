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

## 计划状态

计划状态只在 `plan/index/README.md` 与对应 Plan 文件中维护；本文件不保存状态副本。

## 对话约定

- 涉及计划创建、命名、执行和进度更新时，必须遵守 `constraints/plan-workflow.md`。
- 查询计划状态时优先查看 `plan/index/README.md`；不要把执行计划索引写回 `plan/plan_main.md`。
- 涉及 Java 代码时，必须遵守 `constraints/code-style.md`。
- 涉及 Java 包结构调整时，必须同步更新 `constraints/package-structure.md`。
- 涉及 Docker 部署结构调整时，必须同步更新 `constraints/docker-structure.md`。
- 涉及测试执行方式时，必须遵守 `constraints/test-workflow.md`。
