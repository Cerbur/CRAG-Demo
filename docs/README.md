# CRAG-Demo 文档索引

CRAG-Demo 的工程文档按主题分布。本页是入口，所有相对链接从这里出发。

## Web Console

- [Web Console UI 交接清单](./product/web-console-ui-handoff.md) — Stitch 设计版本、已批准页面状态、设计决策与实现映射。
- [Web Console 工程](../web/README.md) — React + Ant Design Web Console，`docker compose up` 后访问 <http://localhost:3000>。
- 浏览器只访问 3000；Node 同源运行时把 `/console-api`、`/open-api` 代理到对应后端，并重写 Refresh Cookie 的 Path。

## API 契约与前端交接

- [API 前端交接指南](./api/README.md) — Console / Open 正式 HTTP API 的中文联调指南，含登录态、Cookie、Tenant 上下文、分页、上传/轮询/重试、Scope 部分成功、一次性 API Key、Open Query 与统一错误处理。
- [Console API OpenAPI 3.1](./api/console-api.openapi.yaml) — 浏览器管理面契约（auth / tenant / membership / knowledge / document / apikey）。
- [Open API OpenAPI 3.1](./api/open-api.openapi.yaml) — 外部调用方单 KB API Key 问答契约（`POST /api/v1/query`）。
- 契约校验：`python3 scripts/validate_openapi.py`（解析、openapi=3.1、operationId 唯一、`$ref` 可解析、示例匹配、路由清单漂移、Markdown 链接；已纳入 `./gradlew check`）。

## 项目介绍与架构

- [项目介绍](./project_intro.md) — CRAG-Demo 目标、定位与全链路架构说明。
- [架构图](./assets/crag-demo-architecture.svg) — 五进程 Java 拓扑、gRPC 服务通信、PostgreSQL 独立 Schema、Redis Streams 可靠事件基础设施。

## 设计与决策

- [双 API 与摄取生命周期设计](./superpowers/specs/2026-06-28-dual-api-and-ingestion-lifecycle-design.md) — router4 / plan_21 的设计事实来源（已确认并复核）。

> 设计文档是历史事实来源；**当前实现事实以代码、约束文档与本目录 OpenAPI 为准**。OpenAPI 文档不从设计猜测状态码，逐 operation 对应已实现 Controller/DTO。

## 约束与计划入口

- 约束：根目录 [`constraints/`](../constraints/) 维护 Java/HTTP/持久化/Retrieval/包结构/Docker/测试/Plan 工作流约束。
- 计划：[`plan/index/README.md`](../plan/index/README.md) 是计划汇总视图。
