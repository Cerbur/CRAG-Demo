# CRAG-Demo 持久化约束

> 本文档是 Entity、Repository、DAO、事务和 CAS 数据访问规则的唯一维护入口。通用 Java 规则见 `constraints/code-style.md`。

---

## 一、层级职责

### 必须

- Repository 只负责单表持久化、查询声明和结果映射，不包含业务判断。
- Repository 只能由同模块 DAO 调用。
- Service、Controller、Cron 和其他模块禁止直接依赖 Repository。
- DAO 是数据库访问的统一边界，封装 CAS 判定、向量格式转换、持久化投影转换和批量访问等数据库语义。
- DAO 不编排外部 HTTP 服务或跨领域业务流程。
- Repository 返回持久化 Entity 或 storage 投影；跨模块禁止暴露 Repository。

### 推荐

- DAO 方法使用数据访问语义命名，避免为了包装 Repository 而逐方法机械复制其接口。

---

## 二、事务边界

### 必须

- `@Transactional` 放在 Service 或 DAO 的业务操作边界，禁止放在 Controller。
- Repository 不承担跨操作事务编排。
- 事务内禁止执行 Embedding、Rerank、LLM 等耗时外部调用。
- 不依赖同类内部调用触发 Spring 事务代理。

### 推荐

- 查询方法需要事务语义时使用 `readOnly = true`。
- 优先方法级事务，避免用类级 `@Transactional` 模糊边界。
- 事务方法保持短小，并能明确说明包含哪些数据库操作。

---

## 三、自定义更新与 CAS

所有自定义 `@Modifying` 更新必须遵守以下规则。

### Repository

- `WHERE` 子句必须包含当前读取版本，例如 `AND version = :version`。
- `SET` 子句必须在数据库侧原子递增版本，例如 `version = version + 1`。
- Repository 返回 affected rows，不在 Repository 中作业务判断。

### DAO

- DAO 必须检查 affected rows；`affected == 0` 时抛出语义明确的版本冲突异常。
- 禁止将 affected rows 透传给调用方要求其自行判定 CAS 是否成功。
- 禁止使用 `DuplicateKeyException` 冒充版本冲突。
- 如果调用方继续使用已加载 Entity，CAS 成功后必须同步其内存版本；更推荐返回明确的更新结果或重新读取，避免版本漂移。

### 调用方

- 调用方按版本冲突的业务语义处理抢占失败，不得盲目重试。
- 需要记录冲突时使用 `WARN`，并携带实体标识和版本。

### 说明

JPA `@Version` 只在受 EntityManager 管理的更新路径自动生效。自定义更新绕过该机制，因此必须显式完成版本条件与递增。

---

## 四、Entity 与投影

### 必须

- Entity 仅用于持久化边界，不作为 HTTP 响应或新的跨模块公共业务契约。
- `constraints/package-structure.md` 记录的 Storage 迁移期例外只适用于当前已有调用白名单；不得新增 Entity 泄漏。新增跨模块返回值优先使用 storage 投影或明确结果类型。
- Native SQL 或 JPQL 投影的列顺序、别名和类型必须有测试覆盖。
- 数据库特有格式转换由 DAO 或 storage 层适配器负责，禁止泄漏到上层业务服务。

---

## 五、批量数据访问

### 必须

- 禁止在普通循环或 `forEach` 中逐条执行可合并的查询、INSERT 或 UPDATE。
- 查询场景先整理 ID 或条件集合，再批量查询并在内存中按业务顺序组装。
- 写入场景先整理数据集合，再使用批量 insert、update 或 `saveAll`。
- 只有 CAS 抢占、逐条幂等状态推进或单条失败隔离等需要逐条判断数据库结果的场景，才允许逐条 SQL；调用处必须说明原因。
