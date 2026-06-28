# README 与架构图当前态更新设计

## 目标

让仓库首页准确呈现 plan_21 落地后的 CRAG-Demo：五个 Java 进程、Console/Open 双 HTTP 入口、Access/Knowledge/RAG 三个领域服务、gRPC 同步调用、Redis Streams 可靠事件、独立 PostgreSQL Schema，以及完整的摄取与查询链路。

本次按用户要求将 plan_21 能力作为已完成能力展示，不在 README 或架构图中保留“待验收”视觉状态。

## 范围

- 更新 `README.md` 中与当前能力不一致的架构说明、阶段摘要、RAG 管道和项目结构。
- 重绘 `docs/assets/crag-demo-architecture.svg`，以当前产品调用链为主视角。
- 删除已淘汰的独立 Smoke 8083、router1–4 未来态虚线和 plan_17 完成度尾注。
- 不修改业务代码、运行配置、Plan 状态或约束文档。

## 架构图设计

架构图采用自上而下四层布局：

1. **调用方与入口**：管理控制台用户进入 `console-api:8080`；外部调用方进入 `open-api:8081`。
2. **领域服务**：Access 负责身份、Tenant、Membership、JWT、Refresh Session、API Key 与 Scope；Knowledge 负责 KnowledgeBase、Document、文件存储和摄取状态；RAG 负责版本化摄取、混合检索与答案生成。
3. **核心业务流**：区分同步 gRPC、异步 Redis Streams 事件和外部模型 HTTP；突出上传摄取链与 API Key 查询链。
4. **数据与模型基础设施**：PostgreSQL 的 access/knowledge/rag 独立 Schema、Redis、文件存储、Python Sidecar 和 Stub/DeepSeek LLM。

所有已实现链路使用实线。图例只区分通信语义，不再使用“实线已完成、虚线未来”的旧阶段表达。Smoke 作为 Access 8091、Knowledge 8092、RAG 8082 原进程的条件 Profile 注释呈现，不再绘制成独立服务。

## README 设计

- 保留“5 分钟快速开始”和正式 API 契约入口。
- 将“平台架构”说明改成当前态事实与通信图例。
- 把 router2/router3/router4 的历史阶段叙述收敛为“已交付能力”，避免首页像执行日志。
- 修正旧 RAG 七步中 `PENDING_CHUNK`、Cron 主导摄取和旧表名等与当前事件驱动多知识库实现冲突的描述。
- 项目结构补充 `crag-rag-contracts`，并保持当前 Gradle module 清单一致。
- 不复制 Plan 索引状态；详细计划进度继续以 `plan/index/README.md` 为唯一来源。

## 验证

- XML 解析 SVG，检查标题、描述和 README 图片路径。
- 将 SVG 渲染为 PNG 做视觉检查，确认无文字溢出、连线遮挡或内容裁切。
- 对照 `settings.gradle.kts`、`docker-compose.yml`、三个领域 contracts 和 plan_21 已实现任务核对名称、端口与通信方向。
- 运行仓库已有文档/链接静态校验；若没有专项任务，至少运行 `./gradlew check` 中的相关校验入口。

## 非目标与风险

不借此次文档更新改变 Plan 的正式 workflow 状态，也不宣称执行新的独立验收。最大的风险是把演进历史误画成运行时结构，因此架构图只保留稳定运行时边界，Plan 号只在 README 的能力演进摘要中作为追溯链接出现。
