# CRAG-Demo 测试工作流约束

> 本文档是 CRAG-Demo 测试执行方式的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

---

## 一、单元测试例外

- Java 单元测试允许按常规方式运行，例如 `./gradlew test`。
- 单元测试应保持轻量，优先验证纯 Java 逻辑、Mock 依赖和局部业务行为。
- 单元测试不要求启动 Docker 服务。

---

## 二、非单元测试必须走 Docker

以下测试类型必须通过 Docker 启动完整依赖后执行：

- 接口测试
- 集成测试
- 端到端测试
- 冒烟测试
- 手工联调验证
- 需要真实 PostgreSQL、pgvector、Spring Boot 服务或 Python Sidecar 的测试

执行时必须使用 `docker compose` 启动项目内对应服务，再调用容器中服务暴露的接口完成验证。

---

## 三、禁止直接启动服务

非单元测试场景下，禁止绕过 Docker 直接启动 Java 或 Python 服务。

禁止示例：

```bash
./gradlew bootRun
java -jar build/libs/*.jar
python sidecar/main.py
uvicorn main:app
```

允许示例：

```bash
docker compose up --build
docker compose up -d --build
docker compose logs -f
```

---

## 四、接口调用规范

- 测试 Java 后端接口时，调用 Docker Compose 暴露的 Spring Boot 服务端口。
- 测试 Python Sidecar 能力时，调用 Docker Compose 中 Sidecar 暴露的 `/health`、`/embed`、`/rerank` 等接口。
- 不直接 import Python 模块或在宿主机启动 Python 进程来验证 Sidecar 行为。
- 不直接连接宿主机本地临时数据库替代 Compose 中的 PostgreSQL / pgvector。

---

## 五、维护规则

- 新增测试脚本、测试文档或计划验收标准时，必须区分“单元测试”和“非单元测试”。
- 涉及非单元测试的命令示例，必须使用 Docker Compose 方式。
- 如果 Docker 服务名、端口或部署结构变化，必须同步检查本文档与 `constraints/docker-structure.md`。
