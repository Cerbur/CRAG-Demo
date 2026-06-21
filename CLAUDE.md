# CRAG-Demo — 项目索引

## 项目概述

基于 RAG（Retrieval-Augmented Generation）的问答机器人后端服务。技术栈：Java 21 + Spring Boot + Gradle + Docker + pgvector。

## 核心规范

1. **Plan 工作流约束**：统一维护在 `constraints/plan-workflow.md`；本文件只保留路由，不重复展开细则。
2. **代码风格约束**：统一维护在 `constraints/code-style.md`；本文件只保留路由，不重复展开细则。
3. **HTTP API 约束**：统一维护在 `constraints/api-style.md`。
4. **持久化约束**：统一维护在 `constraints/persistence-style.md`。
5. **Retrieval 约束**：统一维护在 `constraints/retrieval-style.md`。
6. **包结构约束**：统一维护在 `constraints/package-structure.md`；本文件只保留路由，不重复展开索引。
7. **Docker 部署结构约束**：统一维护在 `constraints/docker-structure.md`；本文件只保留路由，不重复展开索引。
8. **测试工作流约束**：统一维护在 `constraints/test-workflow.md`；本文件只保留路由，不重复展开细则。
9. **README.md** — 使用中文维护，随项目目标持续更新。
10. **开源协议**：MIT。

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
- 用户要求执行、继续、恢复或修复某个 Plan（包括“执行 plan7”一类短提示）时，必须先读取 `skill/execute-crag-plan/SKILL.md` 并按其执行 session 流程完成实现提交与独立交接；独立验收请求不使用该执行流程。
- 查询计划状态时优先查看 `plan/index/README.md`；不要把执行计划索引写回 `plan/plan_main.md`。
- 涉及 Java 代码时，必须遵守 `constraints/code-style.md`。
- 涉及 Controller、HTTP DTO、统一响应或异常映射时，必须遵守 `constraints/api-style.md`。
- 涉及 Entity、Repository、DAO、事务或 CAS 时，必须遵守 `constraints/persistence-style.md`。
- 涉及 Sparse、Dense、RRF、Rerank 或检索结果类型时，必须遵守 `constraints/retrieval-style.md`。
- 涉及 Java 包结构调整时，必须同步更新 `constraints/package-structure.md`。
- 涉及 Docker 部署结构调整时，必须同步更新 `constraints/docker-structure.md`。
- 涉及测试执行方式时，必须遵守 `constraints/test-workflow.md`。
