# CRAG-Demo 测试工作流约束

> 本文档是 CRAG-Demo 测试执行方式的唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

---

## 一、单元测试例外

- Java 单元测试允许按常规方式运行，例如 `./gradlew test`。
- 单元测试应保持轻量，优先验证纯 Java 逻辑、Mock 依赖和局部业务行为。
- 单元测试不要求启动 Docker 服务。

---

## 二、核心逻辑必须补单元测试

新增或修改必要核心逻辑时，必须同步补充或更新对应单元测试，不允许只写实现不写核心行为验证。

必要核心逻辑包括但不限于：

- RAG 主链路编排逻辑，例如写入、检索、融合、重排、上下文组装和 LLM 调用编排。
- 算法、排序、去重、分数计算、阈值过滤、状态流转和幂等控制。
- DAO / Repository 映射中的非平凡逻辑，例如 native SQL 参数顺序、返回列映射、CAS 更新和向量格式转换。
- Controller / Service 中的请求校验、异常分支、边界输入和错误响应转换。

单元测试必须覆盖：

- 正常路径。
- 空输入、非法输入、依赖返回空结果等边界路径。
- 核心分支和失败路径，例如外部依赖异常、状态不匹配、版本冲突或数据映射异常。

如果某段核心逻辑暂时无法用单元测试覆盖，必须在对应 plan 的验收标准或变更记录中写明原因、替代验证方式和后续补测任务。

---

## 三、非单元测试必须走 Docker

以下测试类型必须通过 Docker 启动完整依赖后执行：

- 接口测试
- 集成测试
- 端到端测试
- 冒烟测试
- 手工联调验证
- 需要真实 PostgreSQL、pgvector、Spring Boot 服务或 Python Sidecar 的测试

执行时必须使用 `docker compose` 启动项目内对应服务，再调用容器中服务暴露的接口完成验证。

---

## 四、新增或大改链路的验证要求

- 如果新增一条可独立验证的业务链路，必须同步增加可通过 HTTP 访问的冒烟验证端点，用于在 Docker 环境中快速确认链路可用。
- 如果对既有链路进行大幅改动，Docker 测试中必须回归该链路的全流程，而不是只验证局部接口或局部服务。

---

## 五、禁止直接启动服务

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

## 六、接口调用规范

- 测试 Java 后端接口时，调用 Docker Compose 暴露的 Spring Boot 服务端口。
- 测试 Python Sidecar 能力时，调用 Docker Compose 中 Sidecar 暴露的 `/health`、`/embed`、`/rerank` 等接口。
- 不直接 import Python 模块或在宿主机启动 Python 进程来验证 Sidecar 行为。
- 不直接连接宿主机本地临时数据库替代 Compose 中的 PostgreSQL / pgvector。

---

## 七、Benchmark / Evaluation Skill 路由

涉及以下测试意图时，必须先查看 `skill/README.md`，并按 `skill/crag-benchmark/SKILL.md` 的工作流执行：

- benchmark、评估、evaluation、评估集、质量评估、链路质量。
- 随机测试数据、golden tests、adversarial examples、distribution samples、对抗性示例、分布样本。
- Retrieval / Query / RAG 的回归测试、前后对比、prompt 变更评估、rerank 参数评估。
- Top1、TopK、命中率、95% CI、置信区间、5 个百分点回归检测、样本量判断。
- 生成或分析 `build/benchmark/` 下的 report。

普通 Java 单元测试仍按本文档单元测试规则执行；涉及真实 Spring Boot、PostgreSQL、pgvector 或 Sidecar 的 benchmark 必须同时遵守 Docker-only 约束。

---

## 八、维护规则

- 新增或修改核心逻辑的 plan 任务，验收标准必须包含对应单元测试要求。
- 新增测试脚本、测试文档或计划验收标准时，必须区分“单元测试”和“非单元测试”。
- 涉及非单元测试的命令示例，必须使用 Docker Compose 方式。
- 如果 Docker 服务名、端口或部署结构变化，必须同步检查本文档与 `constraints/docker-structure.md`。
