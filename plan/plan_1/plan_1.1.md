# Plan_1.1 — 冒烟测试 Controller

> 创建时间：2026-06-10
> 依赖：plan_1（基础设施就绪）
> 父 Plan：plan_1

---

## 范围说明

plan_1 完成后项目骨架已就绪（Gradle + Spring Boot + DAO + Docker），但缺少一个快速验证全链路连通性的入口。plan_1.1 新增一个 TestController，提供冒烟测试端点，验证：

1. HTTP 请求可达（返回 200）
2. PostgreSQL 连接正常（JDBC 联通）
3. 三张表（chunk / chunk_embedding / chunk_fts）均可查询（允许空数据）

**不包含**：Core 业务逻辑、API 业务实现、集成测试框架。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 1.1.1 | 创建 TestController（GET /api/v1/test/smoke） | ✅ 完成 | `—` | 2026-06-10 |
| 1.1.2 | 验证编译通过 | ✅ 完成 | `—` | 2026-06-10 |

> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**2 / 2（100%）**

---

## 任务详情

### 1.1.1 创建 TestController

- **端点**：`GET /api/v1/test/smoke`
- **类路径**：`com.crag.demo.controller.TestController`
- **功能**：
  1. 注入 `ChunkRepository`、`ChunkEmbeddingRepository`、`ChunkFtsRepository`
  2. 查询三张表的 `count()`
  3. 返回 JSON：
     ```json
     {
       "status": "ok",
       "database": "connected",
       "tables": {
         "chunk": 0,
         "chunk_embedding": 0,
         "chunk_fts": 0
       }
     }
     ```
  4. 若数据库连接异常，返回 500 + 错误信息
- **遵循规范**：plan_main 九、代码规范（class Javadoc + @since + field 注释）

### 1.1.2 验证编译

- `./gradlew build` 编译通过
- 确认 TestController 可被 Spring 扫描到

---

## 冒烟验证步骤（手动）

```bash
# 1. 启动 PostgreSQL 容器
docker compose up -d db

# 2. 等待数据库就绪
until docker exec crag-db pg_isready -U crag_user -d crag_demo; do sleep 1; done

# 3. 启动应用
./gradlew bootRun

# 4. 发送冒烟请求
curl http://localhost:8080/api/v1/test/smoke

# 期望响应：
# {"status":"ok","database":"connected","tables":{"chunk":0,"chunk_embedding":0,"chunk_fts":0}}
```

---

## 完成标准

- [ ] `./gradlew build` 编译通过
- [ ] `GET /api/v1/test/smoke` 返回 200 + 三表查询结果
- [ ] DB 不可达时返回 500 + 错误信息

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-10 | 创建 plan_1.1，2 个子任务 |
