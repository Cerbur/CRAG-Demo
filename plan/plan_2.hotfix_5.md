# Plan_2 Hotfix 5 — 抽离 ChunkDao，Cron 不再直接依赖 Repository

> 创建时间：2026-06-13
> 关联：plan_2 任务 2.5

---

## 背景

plan_2 任务 2.5 中 `DenseEmbeddingCron` 直接依赖 `ChunkRepository`，违反了新增的 Repository vs Dao 分层规范（`constraints/code-style.md` 七）。

规范要求：Cron/Service 只依赖 Dao，不直接依赖 Repository。

## 变更内容

### 新增 ChunkDao

```java
@Component
public class ChunkDao {
    @Autowired
    private ChunkRepository chunkRepository;

    public List<Chunk> findDenseCandidates(...) { return chunkRepository.findDenseCandidates(...); }
    public int tryMarkProcessing(...) { return chunkRepository.tryMarkProcessing(...); }
    public int tryMarkProcessingTimeout(...) { return chunkRepository.tryMarkProcessingTimeout(...); }
    public int updateDenseStatus(...) { return chunkRepository.updateDenseStatus(...); }
}
```

一期 Dao 方法直接透传 Repository，不添加额外业务判断。ChunkRepository 的 CAS 方法已是纯 DB 类型映射，Dao 的职责在此为"提供 Cron 层的合法入口"。

### DenseEmbeddingCron 改造

- `ChunkRepository` → `ChunkDao`
- 方法调用不变，仅替换依赖

## 涉及文件

- `src/main/java/com/crag/demo/dao/ChunkDao.java` — 新增（findDenseCandidates + 3 CAS + saveAll + count）
- `src/main/java/com/crag/demo/dao/ChunkEmbeddingDao.java` — 新增 count() 透传
- `src/main/java/com/crag/demo/dao/ChunkFtsDao.java` — 新增（一期仅 count）
- `src/main/java/com/crag/demo/cron/DenseEmbeddingCron.java` — ChunkRepository → ChunkDao
- `src/main/java/com/crag/demo/service/AdminRagService.java` — ChunkRepository → ChunkDao
- `src/main/java/com/crag/demo/controller/TestController.java` — 3 Repository → 3 Dao
- `constraints/package-structure.md` — 新增 ChunkDao、ChunkFtsDao
- `plan/plan_2.md` — 记录 hotfix

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| H5.1 | 新增 ChunkDao（findDenseCandidates + 3 CAS + saveAll + count） | ✅ 完成 | — | 2026-06-13 |
| H5.2 | DenseEmbeddingCron 替换依赖 | ✅ 完成 | — | 2026-06-13 |
| H5.3 | AdminRagService 替换依赖（ChunkRepository → ChunkDao） | ✅ 完成 | — | 2026-06-13 |
| H5.4 | TestController 替换依赖（3 Repository → 3 Dao） + 新增 ChunkFtsDao | ✅ 完成 | — | 2026-06-13 |
| H5.5 | ChunkEmbeddingDao 新增 count() 透传 | ✅ 完成 | — | 2026-06-13 |
| H5.6 | 更新 package-structure + plan_2 | ✅ 完成 | — | 2026-06-13 |
| H5.7 | 编译验证 | ✅ 完成 | — | 2026-06-13 |
| H5.8 | 代码风格修复：TestController → `Response<T>` + 去 try/catch；UserQueryController → `Response<T>` + UserQueryRequest DTO；4 个 Service 全限定名 → 显式 import；AdminRagService/DenseEmbeddingCron/Response 成员变量加 Javadoc | ✅ 完成 | — | 2026-06-13 |
