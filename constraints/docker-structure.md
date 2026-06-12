# CRAG-Demo Docker 部署结构约束

> 本文档是 CRAG-Demo Docker 部署结构索引的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

---

## 一、Docker 部署结构

```text
docker-compose.yml                    — 编排所有服务
Dockerfile                            — Spring Boot 应用镜像
├── PostgreSQL + pgvector 扩展        — 向量数据库
└── Spring Boot 应用                  — 主服务
```

---

## 二、维护规则

- 新增、移动或重命名 Docker / Compose / Sidecar 部署文件时，必须同步更新本文档。
- `AGENTS.md` 与 `CLAUDE.md` 不直接展开 Docker 部署结构，只链接到本文档。
- Docker 部署结构变更如果会影响计划范围，必须同步更新对应 `plan_N.md` 或 `plan_N.hotfix_M.md`。
