# Web API Client 约束

> transport、错误、认证与安全。22.2 落地实现，本文件是规则来源。

## 1. 前缀

- Console API 相对前缀：`/console-api`。
- Open API 相对前缀：`/open-api`。
- Client 不保存容器服务名、宿主机端口或绝对域名；运行时同源代理由 Node Runtime Server（22.9）或本地 Vite 代理处理。

## 2. 认证

- Access Token 只保存在内存 `SessionStore`；禁止写入 `localStorage`、`sessionStorage`、cookie 或 URL。
- Refresh Token 只由 HttpOnly Cookie 管理；浏览器 JS 不可读。
- 并发 401 触发 single-flight refresh：整页同时只发起一次 refresh，每个失败请求最多重放一次。
- refresh 失败必须清空 SessionStore 与 Query 缓存，并跳转登录。

## 3. Client 隔离

- Console client 与 Open client 使用独立实例与认证策略。
- **Open client 不得**：读取 `SessionStore`、提交 `tenantId`、提交 `knowledgeBaseId`、附带 Console Cookie。
- Chat 页面的 API Key 只存在于页面内存；刷新或离开页面即清除。

## 4. 安全红线

以下信息**不得**出现在日志、URL、分析事件、持久化缓存或测试快照：

- 完整 Access Token、Refresh Token。
- 完整 API Key（创建/轮换返回值）。
- 用户密码。
- 后端返回的完整错误响应原文（保留 `traceId` 与摘要即可）。

`console.error` 仅记录 method/path/status/traceId；不打印 Authorization 头或响应体。
