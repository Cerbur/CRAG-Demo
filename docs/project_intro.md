# CRAG-Demo 项目介绍

> 本文档用于后续沉淀 CRAG-Demo 的项目介绍、架构说明、演示脚本与对外展示材料。

## 项目一句话

CRAG-Demo 是一个基于 Java 21 + Spring Boot + PostgreSQL/pgvector 的 RAG 问答机器人后端 Demo，目标是提供可本地一键部署、结构清晰、便于二次开发的检索增强生成链路。

## 核心能力

- 管理端接收纯文本知识内容，完成文档分块与入库。
- 异步构建 Dense 向量索引与 Sparse 全文检索索引。
- 查询端通过 Dense + Sparse 双路召回、RRF 融合、Rerank 重排序后交给 LLM 生成答案。
- 使用 Docker Compose 管理主服务、数据库与模型 Sidecar 依赖。

## 架构图

![CRAG-Demo 全链路架构](./assets/crag-demo-architecture.svg)

## 后续可补充内容

- 项目背景与适用场景
- 本地启动与演示流程
- 核心链路时序说明
- 表结构与状态机说明
- 后续 Roadmap
