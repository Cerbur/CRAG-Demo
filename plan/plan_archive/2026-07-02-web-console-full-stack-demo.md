# Web Console 与全栈 Demo

- **日期**：2026-07-02
- **变更原因**：正式 Console/Open API 已完成，但项目仍缺少用户可操作的管理界面。为让注册、知识库管理、API Key 与检索对话开箱即用，需要增加同仓库 Web Console 并纳入 Docker Compose。
- **关联提交**：`b3e15dac`、`de7327c6`、`43a72b1`

## Before（变更前）

项目定位为 RAG 问答机器人后端和多租户知识平台后端。对外能力停留在 Console API、Open API 与 OpenAPI 文档；Docker Compose 启动后仍需使用脚本或 HTTP 客户端操作。

## After（变更后）

项目定位扩展为开箱即用的全栈 RAG Demo。新增 React + Ant Design Web Console，提供注册登录、Knowledge/Document/API Key 管理和独立 Chat；Web 以 Node 容器运行，通过同源代理接入两个 API，并加入完整 Compose。

## 影响范围

- 受影响的 Plan：新增 `plan_22`，不改写已完成 `plan_21` 的历史范围。
- 受影响的模块：新增 `web/**`，并更新 Docker Compose、README、Docker 与测试约束。
- 受影响的约束：新增 Web 独立约束；根目录 Plan 工作流保持唯一计划来源。

## 迁移与兼容

现有五个 Java 服务、API 路径、宿主机端口和脚本保持兼容。Web 只增加浏览器入口和代理前缀，不改变 Console/Open 对外契约；已有调用方仍可直接访问 `8080/8081`。

## 回滚可能性

可回滚 `plan_22` 的 Web、Compose 服务和文档提交，恢复后端-only Demo。该变化不迁移数据库、不修改持久化数据，也没有不可逆外部操作。
