# CRAG-Demo 代码风格约束

> 本文档是 CRAG-Demo 的代码风格约束唯一维护入口。`AGENTS.md`、`CLAUDE.md` 和计划文档只保留到本文档的路由。

---

## 一、Java Import 规范

- 禁止使用通配符导入：不得出现 `import *`、`import java.util.*`、`import static ...*`。
- 所有依赖必须显式导入到具体类、接口、枚举或静态成员。
- 如果 IDE 自动折叠 import，提交前必须展开为显式 import。

示例：

```java
// 不允许
import java.util.*;

// 允许
import java.util.List;
import java.util.Map;
```

---

## 二、Spring 依赖注入规范

- 优先使用 `@Autowired` 字段注入。
- 不优先在构造器中做依赖注入。
- 除非框架限制、测试构造便利性或不可变性收益明确大于一致性成本，否则不要新增构造器注入。

示例：

```java
@Service
public class AdminRagService {

    @Autowired
    private ChunkSplitService chunkSplitService;
}
```

---

## 三、注释规范

### Class 级别

每个类文件头部必须包含 Javadoc，写明：

```java
/**
 * <一句话功能概述>.
 *
 * <详细说明，2-3 句，描述该类在整体架构中的角色>
 *
 * @since 2026-06-10
 */
```

要求：

- `@since` 标注创建日期，格式为 `YYYY-MM-DD`。
- 必须说清楚该类对应哪个功能模块，与分层架构对应。

### Method 级别

重要 method 必须写 Javadoc，包括 public 方法、核心业务逻辑和算法步骤。

```java
/**
 * <一句话描述该方法做什么>.
 *
 * @param xxx <参数含义>
 * @return <返回值含义>
 */
```

不要求为 getter、setter 或简单委托方法写注释。

### 行注释

复杂逻辑必须加行内注释，例如超过 10 行、包含多重条件、循环或关键算法步骤的代码。

```java
// Step 1: 两路检索并行发出，每路取 Top-K
// Step 2: RRF 按 1/(k+rank) 融合
```

注释写为什么这么做，而不是复述代码。

### 成员变量

有业务语义的成员变量必须注释含义和作用，例如实体字段、算法参数、状态缓存、业务配置值等。

以下基础设施型成员变量不强制写注释，避免产生重复代码本身的噪音：

- `Logger` / `log` 等日志记录器。
- `@Autowired` 注入的 Spring Bean / Dao / Service / Repository / Client。
- `static final` 常量，若命名已清楚表达含义且值本身直观。
- 纯框架适配字段，且字段名与类型已能清晰表达用途。

```java
/**
 * child chunk 在 parent chunk 中的序号，从 0 开始递增.
 * parent chunk 自身此值为 NULL.
 */
private Integer chunkIndex;
```

---

## 四、设计原则

### 奥卡姆剃刀：如无必要，勿增实体

- 不引入当前不需要的抽象层、接口、工具类。
- Demo 阶段不做“万一以后要用”的预留。
- 一个接口只有一个实现时，不做 Interface -> Impl 分离，直接写实现类。

### 多阶段得分类字段规范

当数据对象通过多个处理阶段时，每个阶段应使用独立结果类型表达本阶段的业务语义。类型可以组合上游业务载体，并只新增当前阶段自己产出的字段。

- 禁止同一字段在不同阶段承载不同含义；例如一个 `score` 字段不能先后表示召回分、融合分、重排分。
- 禁止内层返回大而全的外层类型，导致大量字段为 null 或语义未产生。
- 内层结果类型只表达当前阶段已经确定的业务信息；外层通过组合、包装或工厂方法逐步扩展。
- 管道方向应保持“内层窄 → 外层宽”：越外层可以携带越多阶段结果，反向依赖不允许。
- 业务载体字段（如文档、chunk、商品、用户等）可以用 BO/DTO/投影对象组合传递，但不应强迫所有阶段复用持久化 Entity。

反例：

```java
// 不允许：内层阶段返回外层大类型，其中大部分字段尚未产生
public class RecallDao {
    public List<FinalSearchResult> search(...) { ... }
}

// 不允许：score 字段含义随管道变化
public class SearchResult {
    private final double score; // recall / fusion / rerank 混用
}
```

正例：

```java
// 允许：每层返回自己权责范围内的结果类型
public class DenseQueryService {
    public List<DenseSearchResult> search(...) { ... }
}
public class SparseQueryService {
    public List<SparseSearchResult> search(...) { ... }
}
public class RrfFusionService {
    public List<RrfFusionResult> fuse(...) { ... }
}
public class RerankService {
    public List<ChunkSearchResult> rerank(...) { ... }
}
```

当前 retrieval 链路示例：
```
SparseSearchResult  (ChunkBO, sparseScore)      ← SparseQueryService
DenseSearchResult   (ChunkBO, denseScore)       ← DenseQueryService
RrfFusionResult     (ChunkBO, rrfScore + best)  ← RRF 融合
ChunkSearchResult   (ChunkBO, 全部四路得分)     ← Rerank 组装，最外层
```

### 第一性原理：满足功能的最小逻辑

- 每段代码必须回答：最少需要做什么？只做那件事。
- 拒绝过度工程：无状态不用缓存，单线程够用不加锁，数据量小不做分页。
- Demo 阶段硬编码优于配置文件，同步优于异步，手动优于自动化。

---

## 五、统一 API 响应规范

所有 Controller 方法必须遵从此规范。

### 响应类型

- 所有 API 端点必须返回 `Response<T>`（位于 `ai.cerbur.crag.common.dto.result`）。
- 禁止直接返回 `Map<String, Object>`、`ResponseEntity<?>` 或原始业务类型。
- `Response<T>` 包含三个字段：`success` (boolean)、`code` (int)、`result` (T)。

### 构造方式

- 使用静态工厂方法，禁止直接调用构造器：
  - 成功：`Response.success(result)`
  - 错误无 payload：`Response.error(ResponseCode.BAD_REQUEST)`
  - 错误带 payload：`Response.error(ResponseCode.INTERNAL_ERROR, errorDetails)`
- `code` 值必须来自 `ResponseCode` 枚举，不得传入裸整数字面量。

### 请求 DTO

- 请求体参数必须封装为 DTO 类，置于 `ai.cerbur.crag.admin.dto.request` 包。
- 优先使用 Java `record` 定义 DTO。
- 参数校验使用 `@Valid` + Jakarta Bean Validation 注解（`@NotBlank`、`@NotNull` 等），校验失败由 `GlobalExceptionHandler` 统一转为 `Response.error(BAD_REQUEST)`。

### 异常处理

- Controller 方法不写 try/catch —— 异常由 `GlobalExceptionHandler`（`@RestControllerAdvice`）统一拦截转换。
- `MethodArgumentNotValidException` → 400、`IllegalArgumentException` → 400、`Exception`（兜底）→ 500 + 日志。

### 示例

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

---

## 六、DAO CAS 更新规范

所有自定义 `@Modifying` `@Query` 更新方法必须遵守此规范。

### 版本号比对

- 每个 UPDATE 语句的 WHERE 子句必须包含 `AND version = :version`，传入实体当前读到的版本号。
- 每个 UPDATE 语句的 SET 子句必须包含 `version = version + 1`，在数据库侧原子递增。
- 调用方在 CAS 成功后必须手动同步版本号：`entity.setVersion(entity.getVersion() + 1)`。

### 合理性

- JPA `@Version` 仅在 `EntityManager.merge()` / `save()` 路径上自动生效。
- 自定义 `@Query` 绕过 EntityManager，不会自动生成 version 校验。
- 不加 version 校验时，两个并发操作可能基于同一版本读到的数据各自 UPDATE，后执行的会静默覆盖先执行的，造成丢失更新。

### Dao 层 CAS 异常规范

- Dao 层 CAS 更新方法（`updateXxxStatus` 等）**必须**在 `affected == 0` 时抛出 `DuplicateKeyException`。
- **禁止**将 `affected` 返回值透传给调用方，让调用方自行判断 —— 这会导致调用方遗漏检查而静默丢失更新。
- Repository 层仍返回 `int`（纯 DB 操作），业务判断（affected == 0 → 异常）在 Dao 层完成。
- 调用方（Cron / Service）通过 `catch (DuplicateKeyException)` 统一处理版本冲突，包括级联的 FAILED 标记更新。

### 示例

```java
// Repository 侧 —— 纯 DB 操作，返回 affected rows
@Modifying
@Transactional
@Query("UPDATE Chunk c SET c.denseStatus = :newStatus, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.denseStatus = PROCESSING AND c.version = :version")
int updateDenseStatus(@Param("chunkId") String chunkId,
                      @Param("newStatus") ChunkStatus newStatus,
                      @Param("version") Integer version);

// Dao 侧 —— 业务判断：affected == 0 → 抛异常
public int updateDenseStatus(String chunkId, ChunkStatus newStatus, Integer version) {
    int affected = chunkRepository.updateDenseStatus(chunkId, newStatus, version);
    if (affected == 0) {
        throw new DuplicateKeyException(
            "CAS updateDenseStatus failed: chunk " + chunkId + " version " + version + " already stale");
    }
    return affected;
}

// Cron 调用方 —— catch DuplicateKeyException 统一处理版本冲突
try {
    chunkDao.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS, chunk.getVersion());
    successCount++;
} catch (DuplicateKeyException e) {
    // 版本冲突，另一实例已接管
    log.warn("CAS SUCCESS update conflicted for chunk {}", chunk.getChunkId());
}
```

### 禁止事项

- 禁止在自定义 `@Modifying` `@Query` 中省略 `version` 的 WHERE 比对和 SET 递增。
- 禁止使用 JPA `@Version` 的自动行为替代自定义查询中的显式 version 控制——两者作用于不同路径，互不替代。
- 禁止 Dao 层将 CAS 更新的 `affected` 返回值透传给调用方判断——必须在 Dao 层 `affected == 0` 时抛出 `DuplicateKeyException`。

---

## 七、SQL 批量操作规范

在保证逻辑清晰的前提下，SQL 操作应优先整理为一次批量查询或批量写入。

- 禁止在循环或 `forEach` 中对每条数据逐个执行 SQL 查询、INSERT 或 UPDATE。
- 查询场景：先整理本轮需要查询的 ID / 条件集合，再通过批量查询一次取回数据，并在内存中按业务顺序组装结果。
- 写入场景：先整理本轮需要写入或更新的数据集合，再使用批量 insert / update / saveAll 一次提交。
- 只有 CAS 抢占、逐条幂等状态推进、单条失败隔离等确实需要逐条判断并发结果的场景，才允许逐条 SQL 操作；调用处必须通过注释说明原因。

反例：

```java
for (String chunkId : chunkIds) {
    Chunk chunk = chunkDao.findByChunkId(chunkId); // 不允许：N 次 SQL 查询
}
```

正例：

```java
List<Chunk> chunks = chunkDao.findByChunkIds(chunkIds);
Map<String, Chunk> chunkById = chunks.stream()
    .collect(Collectors.toMap(Chunk::getChunkId, Function.identity()));
```

---

## 八、查询链路 BO 组合规范

查询链路（retrieval / query）不得把 `ai.cerbur.crag.storage.entity` 下的 JPA Entity 作为裸返回类型继续透传；retrieval 业务结果类型应组合 `ai.cerbur.crag.retrieval.bo.ChunkBO`。

- Repository / Dao 层可以返回 Entity，因为这是持久化边界内的数据访问模型。
- Storage Dao 可以返回 storage 投影类型；进入 retrieval 业务链路时必须转换为 `ChunkBO`。
- Service 编排层如需对外传递查询结果，必须使用 `retrieval.result` 下的窄类型或宽结果类型，例如 `SparseSearchResult`、`DenseSearchResult`、`RrfFusionResult`、`ChunkSearchResult`。
- 这些结果类型应直接持有 `ChunkBO` 成员，例如 `private final ChunkBO chunk`，让原始 `chunkId`、`parentChunkId`、`chunkIndex`、`content` 沿链路完整传递。
- 禁止把 `List<Chunk>` 作为 retrieval/query 链路的对外返回值；裸 Entity 会让调用方误以为这是完整 DB 查询结果，而不是某一阶段的检索载体。
- 如果第一次查询已经拿到 chunk 原始信息，应沿结果类型继续传递 `ChunkBO`，不要只传 `chunkId` 后再回表补齐。
- 相邻 child 扩展可在 Retrieval 内部调用 Dao 查询 `Chunk`，但必须转换为 `ChunkBO` 并包装为结果类型后再进入 rerank/query 链路。

反例：

```java
Chunk chunk = new Chunk();
chunk.setChunkId(result.getChunkId());
chunk.setContent(result.getContent());
return List.of(chunk); // 不允许：查询链路裸透传 JPA Entity
```

正例：

```java
public class RrfFusionResult {
    private final ChunkBO chunk;
    private final double rrfScore;
}
```

---

## 九、Repository vs Dao 分层规范

项目中存在两种数据访问组件，职责边界如下。

### Repository（Spring Data JPA Interface）

- 纯数据库类型映射：只做列→字段的一一对应。
- 允许：Spring Data 派生查询（`findByXxx`、`existsByXxx`）、`@Query`（JPQL 或 native SQL）。
- 禁止：业务判断逻辑、格式转换、编排多个查询。
- 示例：`ChunkRepository.tryMarkProcessing(...)` — native SQL 做 CAS 更新，WHERE 条件直接对应 DB 列。

### Dao（Component 类）

- 业务数据访问层：包含幂等检查、格式选择、多步查询编排等业务判断。
- 允许：依赖多个 Repository 完成一次业务操作。
- 禁止：直接使用 `JdbcTemplate` 或手写 SQL（SQL 一律放在 Repository 的 `@Query` 中）。
- 禁止：直接依赖 `EntityManager`。
- 示例：`ChunkEmbeddingDao.insert(chunkId, float[] vector)` — 先做 float[] → pgvector 格式转换（业务判断），再委托 `ChunkEmbeddingRepository.insert(chunkId, vectorString)`。

### Cron / Service 层

- 只依赖 Dao，不直接依赖 Repository。
- 不感知 SQL、pgvector 格式、JDBC 等持久化细节。
