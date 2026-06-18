# CRAG-Demo Retrieval 约束

> 本文档是 Sparse、Dense、RRF、Rerank 和检索阶段结果建模的唯一维护入口。通用 Java 规则见 `constraints/code-style.md`。

---

## 一、模块边界

### 必须

- 外部模块只通过 `RetrievalService` 使用检索能力，不依赖 Sparse、Dense、RRF 或 Rerank 内部组件。
- Retrieval 内部实现不得向 Query 或 Admin 泄漏 storage Repository、Entity 或外部 Sidecar 协议类型。
- 外部服务通过能力接口隔离，实现类体现技术方案，例如 `RerankClient` 与 `SidecarRerankClient`。

---

## 二、阶段结果类型

当对象经过多个处理阶段时，每个阶段使用独立结果类型表达本阶段已经产生的业务语义。

### 必须

- 禁止同一字段在不同阶段承载不同含义，例如同一个 `score` 先后表示召回分、融合分和重排分。
- 禁止内层阶段返回外层“大而全”类型并以 `null` 表示尚未产生的字段。
- 阶段类型只新增当前阶段产出的字段，可组合上游稳定业务载体。
- 管道方向保持“内层窄、外层宽”，禁止内层依赖外层结果类型。
- 业务载体可以使用 BO、DTO 或投影组合传递，不强迫所有阶段复用持久化 Entity。

当前链路：

```text
SparseSearchResult  (ChunkBO, sparseScore)      ← SparseQueryService
DenseSearchResult   (ChunkBO, denseScore)       ← DenseQueryService
RrfFusionResult     (ChunkBO, rrfScore + best)  ← RrfFusionService
ChunkSearchResult   (ChunkBO, 全部阶段得分)      ← RerankService
```

---

## 三、排序与分数

### 必须

- 每类分数字段使用能表达来源的名称，例如 `sparseScore`、`denseScore`、`rrfScore`、`rerankScore`。
- 并行召回、融合和重排的最终顺序必须确定；同分时定义稳定的次级排序规则。
- Top-K、阈值、RRF 常数和截断位置必须在代码中有清楚名称；环境无关且只服务算法的不变量可使用常量。
- 分数计算、去重、相邻扩展和排序规则必须有单元测试覆盖正常、空结果、同分和边界输入。

---

## 四、外部调用与并发

### 必须

- Embedding 与 Rerank 调用必须定义超时和失败语义。
- 不得在数据库事务中执行外部检索或模型调用。
- Sparse 与 Dense 并行仅在有可验证延迟收益时引入，并使用项目管理的执行器。
- 并行执行不得改变结果确定性。
- 请求日志不得记录完整文档、完整 Prompt 或向量内容。
