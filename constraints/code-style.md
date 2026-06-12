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

所有成员变量必须注释含义和作用。

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
