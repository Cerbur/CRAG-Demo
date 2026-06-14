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

### 第一性原理：满足功能的最小逻辑

- 每段代码必须回答：最少需要做什么？只做那件事。
- 拒绝过度工程：无状态不用缓存，单线程够用不加锁，数据量小不做分页。
- Demo 阶段硬编码优于配置文件，同步优于异步，手动优于自动化。

---

## 五、统一 API 响应规范

所有 Controller 方法必须遵从此规范。

### 响应类型

- 所有 API 端点必须返回 `Response<T>`（位于 `com.crag.demo.dto.result`）。
- 禁止直接返回 `Map<String, Object>`、`ResponseEntity<?>` 或原始业务类型。
- `Response<T>` 包含三个字段：`success` (boolean)、`code` (int)、`result` (T)。

### 构造方式

- 使用静态工厂方法，禁止直接调用构造器：
  - 成功：`Response.success(result)`
  - 错误无 payload：`Response.error(ResponseCode.BAD_REQUEST)`
  - 错误带 payload：`Response.error(ResponseCode.INTERNAL_ERROR, errorDetails)`
- `code` 值必须来自 `ResponseCode` 枚举，不得传入裸整数字面量。

### 请求 DTO

- 请求体参数必须封装为 DTO 类，置于 `com.crag.demo.dto.request` 包。
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

### 示例

```java
// Repository 侧
@Modifying
@Transactional
@Query("UPDATE Chunk c SET c.denseStatus = :newStatus, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.denseStatus = PROCESSING AND c.version = :version")
int updateDenseStatus(@Param("chunkId") String chunkId,
                      @Param("newStatus") ChunkStatus newStatus,
                      @Param("version") Integer version);

// 调用方
int affected = chunkRepository.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS, chunk.getVersion());
if (affected > 0) {
    chunk.setVersion(chunk.getVersion() + 1);  // DB 已 +1，本地同步
}
```

### 禁止事项

- 禁止在自定义 `@Modifying` `@Query` 中省略 `version` 的 WHERE 比对和 SET 递增。
- 禁止使用 JPA `@Version` 的自动行为替代自定义查询中的显式 version 控制——两者作用于不同路径，互不替代。

---

## 七、Repository vs Dao 分层规范

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
