# Plan_2 — AdminRag 写入链路 + Cron Dense 异步处理

> 创建时间：2026-06-10
> 依赖：plan_1（基础设施就绪）、plan_1.1（冒烟测试就绪）、plan_2.1（Python Sidecar 模型服务，任务 2.4-2.6 的前置条件）

---

## 范围说明

plan_2 实现两条核心链路：

1. **AdminRag 写入链路**：`POST /api/v1/admin/rag` 接收纯文本 → ChunkService 分块（child + parent）→ 写入 chunk 表 → 返回
2. **Cron Dense 异步处理**：`@Scheduled` 定时扫表 → EmbeddingClient 调用 Sidecar `/embed` → 写入 chunk_embedding 表，含幂等状态机（含 processing 超时恢复）

**不包含**：Sparse/FTS 链路、DenseQuery 检索、RRF 融合、Rerank、UserQuery 查询链路、LLM 生成。这些留到后续 plan。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 2.0 | Schema 修正：三张表补齐 version + updated_at | ✅ 完成 | `2dd060e` | 2026-06-10 |
| 2.1 | ChunkService 实现（TokenTextSplitter 分块 → child + parent） | ✅ 完成 | — | 2026-06-12 |
| 2.2 | AdminRagService 实现（编排分块 + 写表） | ✅ 完成 | `24b54ec` | 2026-06-12 |
| 2.3 | AdminRagController 接线（去掉骨架，接入真实逻辑） | ✅ 完成 | — | 2026-06-13 |
| 2.4 | EmbeddingClient 实现（HTTP 调用 Sidecar /embed） | ✅ 完成 | `258a7c5` | 2026-06-13 |
| 2.5 | EmbeddingService 实现（Cron 扫表 + 幂等状态机 + 写 chunk_embedding） | ✅ 完成 | `12b6dd1` | 2026-06-13 |
| 2.6 | 冒烟验证（AdminRag 写入 + Cron Dense 处理） | ✅ 完成 | — | 2026-06-13 |

> **前置条件**：任务 2.4-2.6 依赖 [plan_2.1](./plan_2.1.md)（Python Sidecar 模型服务）完成。Sidecar `/embed` 端点必须可用。
>
> 状态图例：⏳ 待开始 / 🔄 进行中 / ✅ 完成 / ❌ 阻塞

整体进度：**6 / 7（86%）**

---

## 链路总览

```
链路 1（同步）：POST /api/v1/admin/rag
  Controller → Service → ChunkService.split() → ChunkRepository.saveAll() → 返回

链路 2（异步）：@Scheduled Cron
  Cron → 扫 chunk 表（init/failed/超时processing）→ EmbeddingClient.embed()
       → INSERT chunk_embedding → UPDATE dense_status = success/failed
```

---

## 任务详情

### 2.0 — Schema 修正：三张表补齐 version + updated_at

**背景**：plan_1 建表时 `chunk_embedding` 和 `chunk_fts` 缺少 `version` 和 `updated_at`，`chunk` 缺少 `version`。补齐这些字段为后续事件驱动逻辑提供版本幂等和时间追踪能力。

**变更内容**：

| 表 | 新增字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|---|
| chunk | `version` | `INTEGER` | `0` | `@Version` 乐观锁，每次 UPDATE 自动 +1 |
| chunk_embedding | `version` | `INTEGER` | `0` | `@Version` 乐观锁 |
| chunk_embedding | `updated_at` | `TIMESTAMP` | `NOW()` | 记录最后更新时间 |
| chunk_fts | `version` | `INTEGER` | `0` | `@Version` 乐观锁 |
| chunk_fts | `updated_at` | `TIMESTAMP` | `NOW()` | 记录最后更新时间 |

**修正后三表完整结构**：

```sql
-- chunk 表（新增 version）
CREATE TABLE IF NOT EXISTS chunk (
    chunk_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id           UUID NOT NULL,
    parent_chunk_id  UUID,
    chunk_index      INTEGER,
    content          TEXT NOT NULL,
    token_count      INTEGER,
    metadata         JSONB DEFAULT '{}',
    dense_status     SMALLINT DEFAULT 0,
    sparse_status    SMALLINT DEFAULT 0,
    version          INTEGER DEFAULT 0 NOT NULL,     -- 乐观锁版本号
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

-- chunk_embedding 表（新增 version + updated_at）
CREATE TABLE IF NOT EXISTS chunk_embedding (
    chunk_id    UUID PRIMARY KEY REFERENCES chunk(chunk_id) ON DELETE CASCADE,
    embedding   vector(768) NOT NULL,
    version     INTEGER DEFAULT 0 NOT NULL,          -- 乐观锁版本号
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()              -- 最后更新时间
);

-- chunk_fts 表（新增 version + updated_at）
CREATE TABLE IF NOT EXISTS chunk_fts (
    chunk_id    UUID PRIMARY KEY REFERENCES chunk(chunk_id) ON DELETE CASCADE,
    fts_content tsvector NOT NULL,
    version     INTEGER DEFAULT 0 NOT NULL,          -- 乐观锁版本号
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()              -- 最后更新时间
);
```

**JPA 映射**：Chunk / ChunkEmbedding / ChunkFts 三个 Entity 各新增：

```java
/**
 * 乐观锁版本号，每次 UPDATE 自动 +1.
 * 配合 @Version 实现并发安全的 CAS 更新.
 */
@Version
@Column(name = "version")
private Integer version;
```

ChunkEmbedding / ChunkFts 额外新增：

```java
/**
 * 记录最后更新时间，数据库默认 NOW().
 */
@Column(name = "updated_at")
private LocalDateTime updatedAt;
```

**与幂等状态机的关系**：

- `version`：JPA `@Version` 在每次 UPDATE 时自动检查 + 递增。Cron CAS 更新 `dense_status` 时，version 自动 +1，后续事件消费者可通过 version 判断是否已处理过该版本
- `updated_at`：Cron 的 processing 超时检测依赖此字段（`now - updated_at > 5min`），三表统一后语义一致

**涉及文件**：
- `src/main/resources/schema.sql`
- `src/main/java/com/crag/demo/dao/entity/Chunk.java`
- `src/main/java/com/crag/demo/dao/entity/ChunkEmbedding.java`
- `src/main/java/com/crag/demo/dao/entity/ChunkFts.java`
- `plan/plan_main.md` — 5.1.2 Chunk 表结构同步更新

---

### 2.1 — ChunkService 实现

**目标**：将纯文本拆分为 child chunks + parent chunk，返回结构化结果。

**技术选型**：Spring AI `TokenTextSplitter`（暂定，一期不引入额外分词库）。

**数据模型**（内部使用，不持久化）：

```java
/**
 * 分块结果.
 *
 * @param parentChunk  父级大块（1024 token 窗口）
 * @param childChunks  子级小块列表（每个 256 token），用于后续 Embedding
 */
record ChunkResult(ChunkData parentChunk, List<ChunkData> childChunks) {}

record ChunkData(String content, int tokenCount, int chunkIndex) {}
```

- `chunkIndex`：child chunk 在 parent chunk 内的序号（从 0 开始），parent chunk 为 null

**实现要点**：

```java
/**
 * 文档分块服务 —— 将文本按 child chunk（256 token）+ parent chunk（1024 token）拆分.
 *
 * 分块策略：
 * - parent chunk：大窗口（~1024 token），保留完整上下文，不做向量化
 * - child chunk：小粒度（~256 token），用于 Embedding + 检索匹配
 * - child chunk 之间有 overlap（~64 token），减少边界截断损失
 * - 最后一个 child chunk 不满 256 token 也保留
 *
 * @since 2026-06-10
 */
@Service
public class ChunkService {

    // 分块参数（一期硬编码）
    private static final int CHILD_SIZE = 256;   // child chunk token 数
    private static final int PARENT_SIZE = 1024;  // parent chunk token 数
    private static final int OVERLAP = 64;        // child 间重叠 token 数

    /**
     * 将文本拆分为 child + parent chunks.
     *
     * @param content 原始纯文本
     * @return ChunkResult 含 1 个 parent + N 个 child
     */
    public ChunkResult split(String content) {
        // 1. 按 PARENT_SIZE 切分 parent chunk
        // 2. parent chunk 内按 CHILD_SIZE 切分 child chunks（含 overlap）
        // 3. 计算 tokenCount、chunkIndex
        // 返回 ChunkResult
    }

    /**
     * 简易 token 计数（按字符数 / 2 估算，中文 ~1.5 token/char，英文 ~0.75 token/char）.
     * 一期不做精确 tokenizer，后续可替换为 JTokkit 或模型原生 tokenizer.
     */
    private int estimateTokenCount(String text) {
        // 粗略估算：字符数 * 0.6（中英混合平均）
    }
}
```

**关键约束**：
- parent chunk 的 `dense_status = SKIPPED`、`sparse_status = SKIPPED`（不做向量化 / FTS）
- child chunk 的 `dense_status = INIT`、`sparse_status = INIT`（等待异步处理）
- `parent_chunk_id` 关系正确建立

**涉及文件**：
- `src/main/java/com/crag/demo/core/chunk/ChunkService.java` — 从骨架变为完整实现

---

### 2.2 — AdminRagService 实现

**目标**：编排 AdminRag 同步写入链路。

**实现要点**：

```java
/**
 * 管理端 RAG 知识库服务 —— 接收文本，分块写入，返回建库结果.
 *
 * @since 2026-06-10
 */
@Service
public class AdminRagService {

    private final ChunkService chunkService;
    private final ChunkRepository chunkRepository;

    /**
     * 接收纯文本，分块后写入 chunk 表，返回入库结果.
     *
     * @param title    文档标题
     * @param content  文档纯文本内容
     * @param metadata 扩展元数据（tags 等）
     * @return AdminRagResult 含 docId、chunk 数量、状态
     */
    public AdminRagResult ingest(String title, String content, Map<String, Object> metadata) {
        // 1. 生成 docId（UUID）
        // 2. 调 ChunkService.split(content)
        // 3. 构造 Chunk entities：
        //    - parent chunk: dense_status=SKIPPED, sparse_status=SKIPPED
        //    - child chunks: dense_status=INIT, sparse_status=INIT, parentChunkId 指向 parent
        // 4. chunkRepository.saveAll(allChunks)
        // 5. 返回 AdminRagResult(docId, childChunks.size(), "PENDING")
    }
}
```

**返回结构**（对齐 plan_main 3.2）：

```json
{
  "docId": "uuid",
  "chunks": 15,
  "status": "PENDING"
}
```

- `status = "PENDING"` 表示 chunk 已写入，Dense + Sparse 索引异步进行中

**涉及文件**：
- `src/main/java/com/crag/demo/service/AdminRagService.java` — 从骨架变为完整实现

---

### 2.3 — AdminRagController 接线（统一响应封装 + AOP 异常处理）

**目标**：将 `POST /api/v1/admin/rag` 从骨架接入真实逻辑，同时引入项目统一响应封装和全局异常 AOP 层。

**设计决策**：不再使用 `ResponseEntity<AdminRagResponse>` 模式，改为项目级统一 `Response<T>` 泛型包装类。所有控制器方法以此作为返回类型，业务成功/失败由响应体 `success` 和 `code` 字段表达。参数校验使用 `@Valid` + Jakarta Bean Validation 声明式完成，异常由 `@RestControllerAdvice` AOP 层统一拦截转换。

**新增基础设施**（本次一同交付）：

1. `com.crag.demo.dto.result.ResponseCode` — 统一响应码枚举（SUCCESS=0, BAD_REQUEST=400, INTERNAL_ERROR=500）
2. `com.crag.demo.dto.result.Response<T>` — 泛型响应包装类（success, code, result），仅通过静态工厂构造
3. `com.crag.demo.dto.request.AdminRagRequest` — 请求 DTO record（`@NotBlank` 校验 title/content）
4. `com.crag.demo.controller.advice.GlobalExceptionHandler` — AOP 层，统一将异常转为 `Response.error(...)`

**实现要点**：

```java
@RestController
@RequestMapping("/api/v1/admin")
public class AdminRagController {

    @Autowired
    private AdminRagService adminRagService;

    @PostMapping("/rag")
    public Response<AdminRagResult> upload(@Valid @RequestBody AdminRagRequest request) {
        AdminRagResult result = adminRagService.ingest(
            request.title(), request.content(), request.metadata());
        return Response.success(result);
    }
}
```

**返回 JSON 示例**（成功）：

```json
{
  "success": true,
  "code": 0,
  "result": {
    "docId": "550e8400-e29b-41d4-a716-446655440000",
    "chunks": 15,
    "status": "PENDING"
  }
}
```

**涉及文件**：
- `src/main/java/com/crag/demo/dto/result/ResponseCode.java` — 新增
- `src/main/java/com/crag/demo/dto/result/Response.java` — 新增
- `src/main/java/com/crag/demo/dto/request/AdminRagRequest.java` — 新增
- `src/main/java/com/crag/demo/controller/advice/GlobalExceptionHandler.java` — 新增
- `src/main/java/com/crag/demo/controller/AdminRagController.java` — 从骨架变为完整实现
- `build.gradle.kts` — 新增 `spring-boot-starter-validation` 依赖
- `constraints/package-structure.md` — 新增 dto/request、dto/result、controller/advice 包
- `constraints/code-style.md` — 新增统一 API 响应规范章节

---

### 2.4 — EmbeddingClient 实现

**目标**：HTTP 调用 Sidecar Python `/embed` 端点，将文本转为向量。

**实现要点**：

```java
/**
 * Embedding HTTP 客户端 —— 调用 Sidecar Python FastAPI /embed 端点.
 *
 * 协议：POST /embed，body: {"text": "..."} → {"embedding": [0.1, 0.2, ...]}
 * 模型：gte-chinese-base，输出维度 768
 *
 * @since 2026-06-10
 */
@Service
public class SidecarEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final String sidecarUrl;  // 从配置读取，一期默认 http://localhost:8001

    /**
     * 将文本转为向量.
     *
     * @param text 输入文本
     * @return float[768] 稠密向量
     * @throws EmbeddingException 调用失败时抛出，由上层 Cron 捕获并标记 failed
     */
    @Override
    public float[] embed(String text) {
        // 1. POST {sidecarUrl}/embed, body: {"text": text}
        // 2. 解析响应 → float[]
        // 3. 异常处理：超时 / HTTP 错误 → 抛出 EmbeddingException
    }
}
```

**配置项**（添加到 `application.yml`）：

```yaml
crag:
  embedding:
    sidecar-url: http://localhost:8001
    connect-timeout: 5s
    read-timeout: 30s
```

**涉及文件**：
- `src/main/java/com/crag/demo/integration/embedding/SidecarEmbeddingClient.java` — 新增实现类
- `src/main/resources/application.yml` — 添加 embedding 配置

---

### 2.5 — EmbeddingService 实现（Cron + 幂等状态机）

**目标**：定时扫 chunk 表，对 `dense_status` 为 `INIT` 或 `FAILED`（以及超时的 `PROCESSING`）的 child chunk 执行 Embedding，写入 chunk_embedding 表。

#### 设计变更：新增 cron 包

在 `demo` 包下新增 `cron` 包，定时任务统一在此管理：

```
com.crag.demo
├── cron/                              — 新增：定时任务触发层
│   └── DenseEmbeddingCron            — @Scheduled 触发 + 流程编排
├── core/dense/
│   └── DenseEmbeddingService         — 核心 Embedding 调用逻辑（已有骨架，本次完整实现）
├── integration/dense/
│   ├── EmbeddingClient               — 已有接口
│   └── SidecarEmbeddingClient        — 已有实现
└── dao/repository/
    ├── ChunkRepository               — 新增 CAS 更新方法
    └── ChunkEmbeddingRepository       — 已有 upsert
```

**职责划分**：

| 层 | 类 | 职责 |
|---|---|---|
| `cron/` | `DenseEmbeddingCron` | `@Scheduled` 定时扫表 → CAS 抢占 → 调 service → 写状态。只做编排，不含核心处理逻辑 |
| `core/dense/` | `DenseEmbeddingService` | 调用 `EmbeddingClient.embed()` 做向量化，封装重试/异常处理 |
| `integration/dense/` | `SidecarEmbeddingClient` | HTTP 调用 Sidecar `/embed`（已有，不变） |
| `dao/` | `ChunkRepository` | 新增 CAS 更新方法（`tryMarkProcessing`、`updateDenseStatus` 等） |

**DenseEmbeddingCron 伪代码**：

```java
@Component
public class DenseEmbeddingCron {

    private final ChunkRepository chunkRepository;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final DenseEmbeddingService denseEmbeddingService;

    @Scheduled(cron = "*/10 * * * * *")
    void processDenseEmbedding() {
        // 1. 扫表：找出 INIT/FAILED/超时PROCESSING 候选 chunk
        List<Chunk> candidates = chunkRepository.findDenseCandidates(...);

        for (Chunk chunk : candidates) {
            // 2. 根据当前状态 CAS 抢占（T1/T2/T3）
            int affected = switch (chunk.getDenseStatus()) {
                case INIT -> chunkRepository.tryMarkProcessing(chunk.getChunkId(), ChunkStatus.INIT);
                case FAILED -> chunkRepository.tryMarkProcessing(chunk.getChunkId(), ChunkStatus.FAILED);
                case PROCESSING -> chunkRepository.tryMarkProcessingTimeout(chunk.getChunkId(), ...);
                default -> 0;
            };
            if (affected == 0) continue;

            // 3. 调核心逻辑做 Embedding
            try {
                float[] vector = denseEmbeddingService.embed(chunk.getContent());
                chunkEmbeddingRepository.upsert(chunk.getChunkId(), vector);
                chunkRepository.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS);   // T4
            } catch (EmbeddingException e) {
                log.warn("Dense embedding failed for chunk {}, will retry", chunk.getChunkId(), e);
                chunkRepository.updateDenseStatus(chunk.getChunkId(), ChunkStatus.FAILED);    // T5
            }
        }
    }
}
```

核心处理逻辑（调用 Sidecar、异常处理）在 `DenseEmbeddingService` 中，cron 只做编排。后续新增其他定时任务（如 Sparse 处理），同样放入 `cron` 包。

#### 状态机

```
                    ┌── Cron 扫到 ──────────────────────────┐
                    │  (正常)                                 │
                    ▼                                         │
  INIT ──────→ PROCESSING ──→ SUCCESS  (终态)                │
   ▲              │    │                                      │
   │              │    └──→ FAILED ──→ PROCESSING ──→ ...    │
   │              │         (异常)     (Cron 重试)            │
   │              │                                           │
   │              └── 超时恢复 (now - updated_at > 5min) ─────┘
   │                    Cron 扫到 PROCESSING 超时，
   │                    CAS 抢占成功后重新处理
   │
   └── SKIPPED (终态，parent chunk 写入时即设为此状态)
```

#### 状态变更约束表

每个 `UPDATE` 都必须带 WHERE 条件，保证并发安全 + 幂等。

| 编号 | 当前状态 | 目标状态 | 触发者 | UPDATE SQL 约束 | 说明 |
|------|----------|----------|--------|-----------------|------|
| T1 | `INIT` | `PROCESSING` | Cron | `WHERE chunk_id = ? AND dense_status = INIT` | 只抢占仍为 INIT 的行；若已被其他实例抢走，affected=0，跳过 |
| T2 | `FAILED` | `PROCESSING` | Cron | `WHERE chunk_id = ? AND dense_status = FAILED` | 只抢占仍为 FAILED 的行；防止重复重试 |
| T3 | `PROCESSING` | `PROCESSING` | Cron（超时恢复） | `WHERE chunk_id = ? AND dense_status = PROCESSING AND updated_at < NOW() - INTERVAL '5 min'` | 只有**超时**的 PROCESSING 才被抢走，正常的 PROCESSING（updated_at 还在阈值内）不会被干扰 |
| T4 | `PROCESSING` | `SUCCESS` | Cron（embedding 成功） | `WHERE chunk_id = ? AND dense_status = PROCESSING` | 终态，不可逆。INSERT chunk_embedding 需在 UPDATE 之前完成（或同一个事务） |
| T5 | `PROCESSING` | `FAILED` | Cron（embedding 异常） | `WHERE chunk_id = ? AND dense_status = PROCESSING` | 异常降级，下轮 Cron 通过 T2 重试 |
| T6 | — | `SKIPPED` | AdminRagService（写入时） | N/A（INSERT 时直接设值） | 终态，仅 parent chunk。parent 不做 embedding，写入即设为 SKIPPED |

**为什么 T1/T2/T3 是三个独立 SQL 而非合并为一个 `IN (...)`：**

- T1（抢占 INIT）：`dense_status = INIT` — 最严格的约束，只抢未处理过的
- T2（抢占 FAILED）：`dense_status = FAILED` — 只抢已失败的，不影响 INIT
- T3（抢占超时 PROCESSING）：`dense_status = PROCESSING AND updated_at < threshold` — 核心：**带时间条件**，确保只有卡住的 PROCESSING 被回收。正常在处理的（updated_at 刚刚被 T1/T2 刷新过）不会被误抢

在实际代码中，Cron 的扫表 SQL（SELECT）可以用 OR 合并三种候选，但**抢占 UPDATE 应按来源状态分别执行**，各自带各自的 WHERE 约束。

#### Cron 完整流程（伪代码）

```java
@Scheduled(cron = "*/10 * * * * *")
void processDenseEmbedding() {
    // 1. 扫表：找出所有候选 chunk
    List<Chunk> candidates = chunkRepository.findDenseCandidates(
        ChunkStatus.INIT, ChunkStatus.FAILED, 100, Duration.ofMinutes(5));

    for (Chunk chunk : candidates) {
        // 2. 根据当前状态，选择对应的 CAS SQL 抢占
        int affected = switch (chunk.getDenseStatus()) {
            case INIT -> chunkRepository.tryMarkProcessing(chunk.getChunkId(), ChunkStatus.INIT);       // T1
            case FAILED -> chunkRepository.tryMarkProcessing(chunk.getChunkId(), ChunkStatus.FAILED);   // T2
            case PROCESSING -> chunkRepository.tryMarkProcessingTimeout(chunk.getChunkId(),
                Duration.ofMinutes(5));                                                                  // T3
            default -> 0;
        };

        if (affected == 0) continue;  // 被抢走，跳过

        // 3. 执行 embedding
        try {
            float[] vector = embeddingClient.embed(chunk.getContent());
            chunkEmbeddingRepository.upsert(chunk.getChunkId(), vector);  // ON CONFLICT DO UPDATE
            chunkRepository.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS);   // T4
        } catch (EmbeddingException e) {
            log.warn("Dense embedding failed for chunk {}, will retry", chunk.getChunkId(), e);
            chunkRepository.updateDenseStatus(chunk.getChunkId(), ChunkStatus.FAILED);    // T5
        }
    }
}
```

#### chunk_embedding 写入幂等

不使用 upsert（`ON CONFLICT DO UPDATE` 静默覆盖风险过高）。改为先查后插：

```java
// 幂等检查：如果 embedding 已存在（上次写入成功但状态未更新），直接标记成功
if (chunkEmbeddingRepository.existsByChunkId(chunk.getChunkId())) {
    chunkRepository.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS);
    continue;
}

// 不存在 → 普通 INSERT
jdbcTemplate.update(
    "INSERT INTO chunk_embedding (chunk_id, embedding) VALUES (?::uuid, ?::vector)",
    chunkId, toPgVectorString(vector));
```

- 极端并发（两个 Cron 实例同时 INSERT 同一 chunkId）→ `DuplicateKeyException` → 标记 FAILED，下轮重试时 `existsByChunkId` 命中直接标记 SUCCESS。
- embedding 的 update / 定期扫表校验正确性属于独立任务，不在本 Cron 中处理。

#### 并发场景推演

| 场景 | T1/T2/T3 抢占结果 | 最终结果 |
|------|-------------------|----------|
| 两个 Cron 实例同时扫到同一个 INIT chunk | 先执行 UPDATE 的实例 affected=1，后者 affected=0，跳过 | 只有一个实例处理 |
| Cron 实例 A 正在调 embedding（status=PROCESSING，updated_at=刚刚） | Cron 实例 B 扫表：PROCESSING 但 updated_at 在阈值内，不满足 T3 条件，**不会出现在候选集** | 正常处理中，不被干扰 |
| Cron 实例 A 崩溃（status=PROCESSING，updated_at=6分钟前） | 下轮 Cron 扫到：满足 T3 条件 → CAS 抢占 → affected=1 | 超时回收，重新处理 |
| 同一 chunk embedding 连续失败 | T5 → FAILED，T2 → PROCESSING，T5 → FAILED，T2 → ... 无限重试 | 自动重试，不丢数据 |

**超时阈值**：一期硬编码 5 分钟，后续可移至配置文件。

**涉及文件**：
- `src/main/java/com/crag/demo/cron/DenseEmbeddingCron.java` — 新增：定时扫表 + CAS 抢占 + 流程编排
- `src/main/java/com/crag/demo/core/dense/DenseEmbeddingService.java` — 从骨架变为完整实现（核心 Embedding 调用逻辑）
- `src/main/java/com/crag/demo/dao/repository/ChunkRepository.java` — 新增 CAS 更新方法（`findDenseCandidates`、`tryMarkProcessing`、`tryMarkProcessingTimeout`、`updateDenseStatus`），均带 version 乐观锁
- `src/main/java/com/crag/demo/dao/ChunkEmbeddingDao.java` — 新增：chunk_embedding 表 pgvector 操作（JdbcTemplate + 类型转换），封装 existsByChunkId + insert
- `src/main/java/com/crag/demo/dao/repository/ChunkEmbeddingRepository.java` — 新增 `existsByChunkId` 方法
- `src/main/java/com/crag/demo/CragDemoApplication.java` — 开启 `@EnableScheduling`
- `src/main/resources/application.yml` — 新增 `crag.cron.dense` 配置节
- `constraints/package-structure.md` — 新增 cron 包 + dao 子包展开
- `constraints/code-style.md` — 新增「六、DAO CAS 更新规范」章节

---

### 2.6 — 冒烟验证

**验证步骤（手动）**：

```bash
# 前置条件：PostgreSQL 运行中（docker compose up -d db）

# 1. 启动应用
./gradlew bootRun

# 2. 冒烟测试（确认服务可达）
curl http://localhost:8080/api/v1/test/smoke

# 3. 写入文档
curl -X POST http://localhost:8080/api/v1/admin/rag \
  -H "Content-Type: application/json" \
  -d '{
    "title": "测试文档",
    "content": "这是一段很长的测试文本内容..."（足够长以触发分块）
  }'

# 期望返回：
# {"docId":"uuid","chunks":N,"status":"PENDING"}

# 4. 验证 chunk 表写入
# SELECT count(*), dense_status FROM chunk GROUP BY dense_status;
# 期望：有 1 个 SKIPPED（parent）+ N 个 INIT（children）

# 5. 等待 Cron 触发（最多 10 秒），或手动查 chunk_embedding 表
# SELECT count(*) FROM chunk_embedding;
# 期望：与 child chunk 数量一致（假设 Sidecar embedding 可用）
# 注意：若 Sidecar 不可用，dense_status 应为 FAILED，Cron 会自动重试
```

**完成标准**：
- [x] `POST /api/v1/admin/rag` 返回 200 + docId + chunks + PENDING
- [x] chunk 表正确写入 parent + child，dense/sparse status 正确
- [x] Cron 正确扫到 INIT chunk，更新为 PROCESSING → SUCCESS
- [x] chunk_embedding 表正确写入向量（Sidecar 768-dim gte-chinese-base 可用）
- [ ] Processing 超时 chunk 被 Cron 重新捞起处理（需模拟崩溃场景，留到后续验证）

---

## 配置汇总

`application.yml` 新增项：

```yaml
crag:
  embedding:
    sidecar-url: http://localhost:8001
    connect-timeout: 5s
    read-timeout: 30s
  cron:
    dense:
      batch-size: 100         # 每轮最多处理数
      interval-ms: 10000      # Cron 间隔（毫秒）
      processing-timeout: 5m  # processing 超时阈值
```

> 一期所有 cron 配置硬编码（`@Scheduled(cron = "*/10 * * * * *")`），配置文件中的值预留后续调整。

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-06-10 | 创建 plan_2，6 个子任务：AdminRag 写入链路 + Cron Dense 异步处理 |
| 2026-06-12 | 2.0 确认完成（schema 三表 version + updated_at 已于 plan_1 阶段补齐）；2.1 ChunkService 完整实现（Spring AI TokenTextSplitter 真实 token 级分块 + JTokkit CL100K_BASE 编码 + child/parent 二级策略 + overlap） |
| 2026-06-13 | 2.3 设计更新：从 ResponseEntity 改为统一 Response<T> 包装 + ResponseCode 枚举；新增 dto/request、dto/result、controller/advice 子包；新增 GlobalExceptionHandler AOP 层；AdminRagRequest 使用 @Valid + @NotBlank 校验；添加 spring-boot-starter-validation 依赖；同步更新 package-structure 和 code-style 约束 |
| 2026-06-13 | 2.5 设计更新：新增 cron 包（DenseEmbeddingCron）；ChunkRepository CAS 方法加 version 乐观锁；chunk_embedding 写入从 upsert 改为先查后插；新增 ChunkEmbeddingDao（Repository vs Dao 分层） |
| 2026-06-13 | [plan_2.hotfix_5](./plan_2.hotfix_5.md)：抽离 ChunkDao，DenseEmbeddingCron 不再直接依赖 ChunkRepository |
| 2026-06-13 | 2.6 冒烟验证完成。发现并修复 4 个问题：(1) Chunk 实体 @Version + @GeneratedValue 冲突 → 实现 Persistable<String> + 移除 @GeneratedValue + 手动设置 child chunkId；(2) metadata JSONB 类型映射缺失 → 添加 @JdbcTypeCode(SqlTypes.JSON)；(3) ChunkEmbeddingRepository.insert() native query 参数绑定错误（?1/?2 与 @Param 冲突，以及 :param::cast 解析异常）→ 改用 CAST(?1 AS uuid) 无 @Param 形式；(4) DenseEmbeddingCron 异常捕获过窄 → 新增 RuntimeException 兜底 catch 避免 chunk 卡在 PROCESSING |
