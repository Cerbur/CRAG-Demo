# plan_4 — Sparse 落库 + Sparse/Dense 查询 + RRF 融合

> 创建日期：2026-06-14  
> 状态：⏳ 待开始  

## 范围

本计划覆盖三件事：
1. **Sparse 写入链路** — SparseEmbeddingCron 定时扫表，为 child chunk 生成 tsvector 写入 chunk_fts
2. **Sparse + Dense 双路查询** — 基于 pgvector 向量相似度 + PostgreSQL FTS 全文检索的两路并行检索
3. **RRF 融合** — Reciprocal Rank Fusion 对两路结果去重、融合、回表 parent 上下文

**不包含**：Rerank、UserQuery 全链路串联、LLM 调用。这些留到 plan_5。

---

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 4.1 | ChunkRepository — Sparse CAS 方法补充 | ⏳ | — | — |
| 4.2 | ChunkFtsRepository — INSERT / existsByChunkId | ⏳ | — | — |
| 4.3 | ChunkFtsDao — 写入方法补充 | ⏳ | — | — |
| 4.4 | ChunkDao — Sparse 读写方法透传 | ⏳ | — | — |
| 4.5 | SparseEmbeddingCron — 定时任务 | ⏳ | — | — |
| 4.6 | 通用查询结果类型 ChunkSearchResult | ⏳ | — | — |
| 4.7 | ChunkEmbeddingRepository — 向量相似度查询 | ⏳ | — | — |
| 4.8 | ChunkEmbeddingDao — searchSimilar 方法 | ⏳ | — | — |
| 4.9 | DenseQueryService 实现 | ⏳ | — | — |
| 4.10 | ChunkFtsRepository — FTS 全文检索查询 | ⏳ | — | — |
| 4.11 | ChunkFtsDao — searchFts 方法 | ⏳ | — | — |
| 4.12 | SparseQueryService 实现 | ⏳ | — | — |
| 4.13 | RrfFusionService 实现 | ⏳ | — | — |
| 4.14 | TestController — 查询冒烟验证 | ⏳ | — | — |

整体进度：0 / 14（0%）

---

## 4.1 ChunkRepository — Sparse CAS 方法补充

**文件**：`dao/repository/ChunkRepository.java`

当前 ChunkRepository 的 CAS 方法只覆盖 `denseStatus`（`tryMarkProcessing`、`tryMarkProcessingTimeout`、`updateDenseStatus`），Sparse 链路缺少独立的 CAS 更新。需新增 4 个方法，与 Dense 侧完全对称：

### 4.1.1 findSparseCandidates

扫描待处理的 child chunk，条件：
- `parentChunkId <> ''`（仅 child chunk）
- `sparseStatus IN (INIT, FAILED)` 或 `(sparseStatus = PROCESSING AND updatedAt < :timeoutThreshold)`
- 按 `updatedAt ASC` 排序，分页限制

```java
@Query("SELECT c FROM Chunk c WHERE c.parentChunkId <> '' AND (c.sparseStatus IN :statuses OR (c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.updatedAt < :timeoutThreshold)) ORDER BY c.updatedAt ASC")
List<Chunk> findSparseCandidates(@Param("statuses") List<ChunkStatus> statuses,
                                 @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
                                 Pageable pageable);
```

### 4.1.2 tryMarkSparseProcessing

CAS 抢占：将 `sparseStatus` 从 `expectedStatus`（INIT 或 FAILED）改为 PROCESSING，WHERE 条件包含版本号比对。

```java
@Modifying @Transactional
@Query("UPDATE Chunk c SET c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.sparseStatus = :expectedStatus AND c.version = :version")
int tryMarkSparseProcessing(@Param("chunkId") String chunkId,
                             @Param("expectedStatus") ChunkStatus expectedStatus,
                             @Param("version") Integer version);
```

### 4.1.3 tryMarkSparseProcessingTimeout

CAS 超时回收：仅当 `sparseStatus = PROCESSING` 且 `updatedAt < threshold` 且版本匹配时更新。

```java
@Modifying @Transactional
@Query("UPDATE Chunk c SET c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.updatedAt < :timeoutThreshold AND c.version = :version")
int tryMarkSparseProcessingTimeout(@Param("chunkId") String chunkId,
                                    @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
                                    @Param("version") Integer version);
```

### 4.1.4 updateSparseStatus

终态更新：将 PROCESSING 改为 SUCCESS 或 FAILED，WHERE 条件包含 `sparseStatus = PROCESSING` 和版本号。

```java
@Modifying @Transactional
@Query("UPDATE Chunk c SET c.sparseStatus = :newStatus, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.version = :version")
int updateSparseStatus(@Param("chunkId") String chunkId,
                        @Param("newStatus") ChunkStatus newStatus,
                        @Param("version") Integer version);
```

**验收**：4 个新方法编译通过，JPQL 语法正确，`sparseStatus` 枚举引用路径正确。已有 `findBySparseStatusIn` 保留不变。

---

## 4.2 ChunkFtsRepository — INSERT / existsByChunkId 补充

**文件**：`dao/repository/ChunkFtsRepository.java`

当前 ChunkFtsRepository 仅有 JPA 基础 CRUD。参考 ChunkEmbeddingRepository 模式，新增 2 个方法：

### 4.2.1 existsByChunkId

```java
boolean existsByChunkId(String chunkId);
```

Spring Data 派生查询，直接用方法名。

### 4.2.2 insert

Native SQL，调用方传入原始文本，SQL 侧做 CJK 空格正则 + `to_tsvector('simple', ...)`：

```java
@Modifying
@Transactional
@Query(value = """
    INSERT INTO chunk_fts (chunk_id, fts_content)
    VALUES (CAST(?1 AS uuid),
            to_tsvector('simple',
                regexp_replace(?2, '([一-龥])', '\\1 ', 'g')))
    """, nativeQuery = true)
void insert(String chunkId, String rawContent);
```

说明：
- `regexp_replace(?2, '([一-龥])', '\\1 ', 'g')` —— 在每个 CJK 统一汉字（U+4E00–U+9FA5）后插入空格，使每个字成为独立 token
- `to_tsvector('simple', ...)` —— 不区分大小写，按空格分词
- `CAST(?1 AS uuid)` —— 将 String 转 UUID 类型，与 chunk 表外键类型对齐

**验收**：编译通过，`existsByChunkId` 方法签名正确，`insert` 的 native SQL 参数顺序与调用方一致。

---

## 4.3 ChunkFtsDao — 写入方法补充

**文件**：`dao/ChunkFtsDao.java`

当前仅有 `count()` 透传。新增 2 个方法，模式对齐 ChunkEmbeddingDao：

### 4.3.1 existsByChunkId

```java
public boolean existsByChunkId(String chunkId) {
    return chunkFtsRepository.existsByChunkId(chunkId);
}
```

### 4.3.2 insert

先幂等检查再插入，格式转换在 Repository 层 native SQL 完成（Dao 层不做中文预处理）：

```java
public void insert(String chunkId, String rawContent) {
    if (chunkFtsRepository.existsByChunkId(chunkId)) {
        log.debug("FTS already exists for chunk {}, skipping", chunkId);
        return;
    }
    chunkFtsRepository.insert(chunkId, rawContent);
    log.debug("FTS inserted — chunkId={}", chunkId);
}
```

添加 Logger：
```java
private static final Logger log = LoggerFactory.getLogger(ChunkFtsDao.class);
```

**验收**：`existsByChunkId` 和 `insert` 正确委托 Repository，幂等逻辑与 ChunkEmbeddingDao 对齐。

---

## 4.4 ChunkDao — Sparse 读写方法透传

**文件**：`dao/ChunkDao.java`

新增 4 个透传方法，将 4.1 的 Repository 方法暴露给 Cron 层：

```java
public List<Chunk> findSparseCandidates(List<ChunkStatus> statuses,
                                         LocalDateTime timeoutThreshold,
                                         Pageable pageable) {
    return chunkRepository.findSparseCandidates(statuses, timeoutThreshold, pageable);
}

public int tryMarkSparseProcessing(String chunkId, ChunkStatus expectedStatus, Integer version) {
    return chunkRepository.tryMarkSparseProcessing(chunkId, expectedStatus, version);
}

public int tryMarkSparseProcessingTimeout(String chunkId, LocalDateTime timeoutThreshold, Integer version) {
    return chunkRepository.tryMarkSparseProcessingTimeout(chunkId, timeoutThreshold, version);
}

public int updateSparseStatus(String chunkId, ChunkStatus newStatus, Integer version) {
    return chunkRepository.updateSparseStatus(chunkId, newStatus, version);
}
```

验收：4 个方法签名与 Repository 对齐，编译通过。

---

## 4.5 SparseEmbeddingCron — 定时任务

**文件**：`cron/SparseEmbeddingCron.java`（新建）

完全镜像 `DenseEmbeddingCron`，差异点：

| 对比维度 | DenseEmbeddingCron | SparseEmbeddingCron |
|---|---|---|
| 扫表方法 | `chunkDao.findDenseCandidates(...)` | `chunkDao.findSparseCandidates(...)` |
| CAS 抢占 | `chunkDao.tryMarkProcessing(...)` | `chunkDao.tryMarkSparseProcessing(...)` |
| CAS 超时 | `chunkDao.tryMarkProcessingTimeout(...)` | `chunkDao.tryMarkSparseProcessingTimeout(...)` |
| 核心处理 | `denseEmbeddingService.embed(content)` → 调 Sidecar HTTP | `chunkFtsDao.insert(chunkId, content)` → 直接写 DB |
| 幂等检查 | `chunkEmbeddingDao.existsByChunkId(...)` | `chunkFtsDao.existsByChunkId(...)` |
| 终态更新 | `chunkDao.updateDenseStatus(...)` | `chunkDao.updateSparseStatus(...)` |
| 失败异常 | `EmbeddingException`, `DuplicateKeyException` | `DuplicateKeyException`（无外部 HTTP 调用，异常来源只有 DB 写入冲突） |

关键参数：
```java
private static final int BATCH_SIZE = 100;
private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);
private static final List<ChunkStatus> CANDIDATE_STATUSES = Arrays.asList(ChunkStatus.INIT, ChunkStatus.FAILED);
```

定时表达式：`@Scheduled(cron = "*/10 * * * * *")`（与 Dense Cron 相同，每 10 秒）

依赖注入：
```java
@Autowired private ChunkDao chunkDao;
@Autowired private ChunkFtsDao chunkFtsDao;
```

异常处理：与 Dense Cron 不同，Sparse 不调外部 HTTP，不会抛 `EmbeddingException`。异常来源只有：
- `DuplicateKeyException`：极端并发重复写入 → 下轮 existsByChunkId 命中 → 直接标记 SUCCESS
- `RuntimeException`：未预期异常 → 标记 FAILED，下轮重试

**验收**：
- 编译通过，`@Scheduled` 注解正确
- 逻辑结构与 DenseEmbeddingCron 一致，差异仅在上表所列
- Javadoc 标明职责边界

---

## 4.6 通用查询结果类型 ChunkSearchResult

**文件**：`core/common/ChunkSearchResult.java`（新建包 `core/common/`）

定义两路查询统一返回类型，供 SparseQueryService、DenseQueryService、RrfFusionService 共用：

```java
package com.crag.demo.core.common;

/**
 * 单条检索结果 —— Sparse/Dense 查询统一返回类型.
 *
 * 包含 child chunk 的关键信息及父 chunk 引用，供 RRF 融合时回表 parent 上下文.
 *
 * @param chunkId       child chunk ID
 * @param parentChunkId 父 chunk ID，用于 RRF 回表获取完整上下文
 * @param score         相关性分数（Dense: cosine similarity, Sparse: ts_rank）
 * @param content       child chunk 文本内容
 * @since 2026-06-14
 */
public record ChunkSearchResult(String chunkId, String parentChunkId, float score, String content) {}
```

**验收**：编译通过，包路径 `com.crag.demo.core.common` 创建。同步更新 `constraints/package-structure.md`，在 `core/` 下添加 `common/` 条目。

---

## 4.7 ChunkEmbeddingRepository — 向量相似度查询

**文件**：`dao/repository/ChunkEmbeddingRepository.java`

新增 native SQL 查询方法，用 pgvector `<=>` 运算符做余弦距离排序，JOIN chunk 表取 content 和 parentChunkId：

```java
@Query(value = """
    SELECT c.chunk_id, c.parent_chunk_id, c.content, 1 - (e.embedding <=> CAST(?1 AS vector)) AS score
    FROM chunk_embedding e
    JOIN chunk c ON c.chunk_id = e.chunk_id
    ORDER BY e.embedding <=> CAST(?1 AS vector)
    LIMIT ?2
    """, nativeQuery = true)
List<Object[]> searchSimilar(String vectorString, int topK);
```

说明：
- `<=>` 是 pgvector 的余弦距离运算符
- `1 - cosine_distance` = cosine similarity
- JOIN chunk 表一次取出 content 和 parentChunkId，避免后续 N+1 查询
- 返回 `Object[]`（4 列：chunk_id, parent_chunk_id, content, score），由 Dao 层映射为 ChunkSearchResult

**验收**：native SQL 语法正确，参数顺序与 Dao 调用对齐，返回列顺序明确。

---

## 4.8 ChunkEmbeddingDao — searchSimilar 方法

**文件**：`dao/ChunkEmbeddingDao.java`

新增 `searchSimilar` 方法：float[] → pgvector 字面量转换 + 委托 Repository + Object[] → ChunkSearchResult 映射：

```java
public List<ChunkSearchResult> searchSimilar(float[] queryVector, int topK) {
    String vectorString = toPgVectorString(queryVector);
    List<Object[]> rows = chunkEmbeddingRepository.searchSimilar(vectorString, topK);
    return rows.stream().map(row -> new ChunkSearchResult(
        (String) row[0],    // chunk_id
        (String) row[1],    // parent_chunk_id
        ((Number) row[3]).floatValue(),  // score
        (String) row[2]     // content
    )).toList();
}
```

说明：
- 复用已有 `toPgVectorString(float[])` 方法（格式 `[0.1,0.2,...]`）
- `(Number) row[3].floatValue()` 兼容 PostgreSQL 返回 Double 的情况
- 列顺序与 Repository 的 SELECT 子句严格对齐

新增 import：
```java
import com.crag.demo.core.common.ChunkSearchResult;
```

**验收**：编译通过，Object[] 列索引与 Repository SELECT 顺序一致，float[] 格式转换正确。

---

## 4.9 DenseQueryService 实现

**文件**：`core/dense/DenseQueryService.java`

将骨架方法改为实际实现：

```java
@Autowired
private ChunkEmbeddingDao chunkEmbeddingDao;

public List<ChunkSearchResult> search(float[] queryEmbedding, int topK) {
    if (queryEmbedding == null || queryEmbedding.length == 0) {
        return List.of();
    }
    return chunkEmbeddingDao.searchSimilar(queryEmbedding, topK);
}
```

修改方法签名和返回类型：
- `public List<?> search(...)` → `public List<ChunkSearchResult> search(...)`

新增 import：
```java
import com.crag.demo.core.common.ChunkSearchResult;
import com.crag.demo.dao.ChunkEmbeddingDao;
```

**验收**：编译通过，骨架 `Collections.emptyList()` 替换为实际调用。

---

## 4.10 ChunkFtsRepository — FTS 全文检索查询

**文件**：`dao/repository/ChunkFtsRepository.java`

新增 native SQL 查询，使用 PostgreSQL FTS `@@` 运算符和 `ts_rank` 做 BM25 风格相关性排序：

```java
@Query(value = """
    SELECT c.chunk_id, c.parent_chunk_id, c.content, ts_rank(f.fts_content, query) AS score
    FROM chunk_fts f
    JOIN chunk c ON c.chunk_id = f.chunk_id,
         plainto_tsquery('simple', regexp_replace(?1, '([一-龥])', '\\1 ', 'g')) AS query
    WHERE f.fts_content @@ query
    ORDER BY score DESC
    LIMIT ?2
    """, nativeQuery = true)
List<Object[]> searchFts(String queryText, int topK);
```

说明：
- `plainto_tsquery('simple', regexp_replace(..., '([一-龥])', ...))` —— 将用户查询做与 4.2.2 写入时相同的 CJK 正则预处理，保证查询和索引的分词方式一致
- `@@` —— tsvector 匹配 tsquery
- `ts_rank` —— Postgres 默认相关度排序函数（基于词频/文档频率）
- `FROM ... , LATERAL` 的逗号写法将 `plainto_tsquery(...)` 计算为单个值，避免在 WHERE 和 ORDER BY 中重复计算

**验收**：native SQL 语法正确，CJK 正则与 4.2.2 INSERT 保持一致。

---

## 4.11 ChunkFtsDao — searchFts 方法

**文件**：`dao/ChunkFtsDao.java`

新增查询透传方法，将 `Object[]` 映射为 `ChunkSearchResult`：

```java
public List<ChunkSearchResult> searchFts(String query, int topK) {
    if (query == null || query.isBlank()) {
        return List.of();
    }
    List<Object[]> rows = chunkFtsRepository.searchFts(query.trim(), topK);
    return rows.stream().map(row -> new ChunkSearchResult(
        (String) row[0],    // chunk_id
        (String) row[1],    // parent_chunk_id
        ((Number) row[3]).floatValue(),  // score (ts_rank)
        (String) row[2]     // content
    )).toList();
}
```

新增 import：
```java
import com.crag.demo.core.common.ChunkSearchResult;
import java.util.List;
```

**验收**：编译通过，空查询返回空列表，列映射顺序与 Repository SELECT 对齐。

---

## 4.12 SparseQueryService 实现

**文件**：`core/sparse/SparseQueryService.java`

将骨架方法改为实际实现：

```java
@Autowired
private ChunkFtsDao chunkFtsDao;

public List<ChunkSearchResult> search(String query, int topK) {
    if (query == null || query.isBlank()) {
        return List.of();
    }
    return chunkFtsDao.searchFts(query, topK);
}
```

修改方法签名：`public List<?> search(...)` → `public List<ChunkSearchResult> search(...)`

新增 import：
```java
import com.crag.demo.core.common.ChunkSearchResult;
import com.crag.demo.dao.ChunkFtsDao;
import java.util.List;
```

**验收**：编译通过，骨架替换为实际 FTS 查询。

---

## 4.13 RrfFusionService 实现

**文件**：`core/rrf/RrfFusionService.java`

### 算法

1. 接收 Sparse 和 Dense 两路 `List<ChunkSearchResult>`（均已按各自分数降序排列）
2. 对每路结果依次赋予 1-based rank（第 1 名 rank=1）
3. RRF 公式：`score(chunk) = Σ 1 / (K + rank_i)`，其中 K=60，i 遍历该 chunk 出现的所有路
4. 按 RRF 分数降序排序，取 topN
5. 通过 `parentChunkId` 回表 chunk 表获取 parent 完整上下文
6. 同一 parent 下多个 child 命中时，取最大 RRF 分数去重
7. 返回排序后的去重 parent chunk 列表

### 依赖

```java
@Autowired
private ChunkDao chunkDao;
```

注入 ChunkDao 做 parent 回表查询（通过 `parentChunkId` 找 parent chunk 记录）。

### 方法签名和返回类型

原骨架：
```java
public List<?> fuse(List<?> sparseResults, List<?> denseResults, int topN)
```

改为：
```java
public List<ChunkSearchResult> fuse(List<ChunkSearchResult> sparseResults,
                                     List<ChunkSearchResult> denseResults,
                                     int topN)
```

返回的 `ChunkSearchResult` 中：
- `chunkId` = parent chunk ID
- `parentChunkId` = `""`  (parent 自身无父节点)
- `score` = RRF 融合分数
- `content` = parent chunk 完整文本内容

### 实现步骤（伪代码）

```java
// Step 1: 构建 chunkId → RRF score 累加 map
// Step 2: 遍历 sparseResults，对 rank i: scoreMap.merge(chunkId, 1/(K+i), Float::sum)
// Step 3: 遍历 denseResults，对 rank j: scoreMap.merge(chunkId, 1/(K+j), Float::sum)
// Step 4: 按 RRF score 降序排序，取 topN
// Step 5: 通过 chunkId 收集 parentChunkId，构建 parentChunkId → maxRRFScore map
// Step 6: 批量查询 parent chunk（findAllById），构建结果列表
// Step 7: 按 RRF score 降序排序返回
```

K 值保持现有常量 `RRF_K = 60`。

**验收**：
- 编译通过，类型从 `List<?>` 收敛为 `List<ChunkSearchResult>`
- RRF 分数计算正确：`1/(60+rank)` 而非 `1/(rank)`
- 相同 parent 下多个 child 去重正确（取最大 RRF 分数）
- 返回结果按 RRF score 降序排列

---

## 4.14 TestController — 查询冒烟验证

**文件**：`controller/TestController.java`

现在的 `GET /api/v1/test/smoke` 只验证写入侧（三表 count）。新增查询冒烟端点，验证双路检索 + RRF 融合端到端可通：

### 4.14.1 新增测试端点 `GET /api/v1/test/search`

```java
@Autowired private SparseQueryService sparseQueryService;
@Autowired private DenseQueryService denseQueryService;
@Autowired private EmbeddingClient embeddingClient;  // 已有
@Autowired private RrfFusionService rrfFusionService;

@GetMapping("/search")
public Response<Map<String, Object>> search(@RequestParam(defaultValue = "测试查询") String q) {
    // 1. 将 query 转为向量
    float[] queryVector = embeddingClient.embed(q);

    // 2. 两路并行检索
    List<ChunkSearchResult> sparseResults = sparseQueryService.search(q, 20);
    List<ChunkSearchResult> denseResults = denseQueryService.search(queryVector, 20);

    // 3. RRF 融合
    List<ChunkSearchResult> fused = rrfFusionService.fuse(sparseResults, denseResults, 10);

    // 4. 返回各阶段结果数量
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("query", q);
    result.put("sparseCount", sparseResults.size());
    result.put("denseCount", denseResults.size());
    result.put("fusedCount", fused.size());
    result.put("fusedResults", fused);
    return Response.success(result);
}
```

新增 import：
```java
import com.crag.demo.core.common.ChunkSearchResult;
import com.crag.demo.core.dense.DenseQueryService;
import com.crag.demo.core.sparse.SparseQueryService;
import com.crag.demo.core.rrf.RrfFusionService;
import com.crag.demo.integration.dense.EmbeddingClient;
```

### 4.14.2 更新 package-structure.md

在 `core/` 下新增：
```
│   ├── common/                       — 核心共享类型
│   │   └── ChunkSearchResult        — 统一检索结果记录
```

**验收**：
- `GET /api/v1/test/search?q=...` 返回 200，包含 sparseCount、denseCount、fusedCount
- 配合已有写入数据验证端到端可通（需先通过 AdminRag 入库，等待 Dense + Sparse Cron 处理完成）
