---
workflow_version: 3
plan_id: plan_10.hotfix_1
type: hotfix
parent_plan: plan_10
status: ready
created: 2026-06-25
updated: 2026-06-25
---

# plan_10.hotfix_1 — Docker 回归脚本 wait_for_http_status 计时修正

## 背景与目标

`plan_15` 第四次独立验收的 Docker HTTP 重跑发现 `scripts/tests/http/docker_readiness_test.sh` 测试 6（数据库故障恢复）长时间卡在 `等待 rag-service readiness 返回 HTTP 503`，实际耗时远超脚本声明的 120s 上限（观察约 6 分钟仍未推进，终止后估算完整超时需 ~16 分钟）。

根因（`docker_readiness_test.sh` 由 `plan_10/10.2` 引入，`wait_for_http_status` 为其有界轮询工具函数）：

```bash
wait_for_http_status() {
  ...
  while [ $elapsed -lt "$max_wait" ]; do
    status=$(curl -s -m 35 ... "$url" ...) || status="000"   # 每次最多阻塞 35s
    ...
    sleep 5
    elapsed=$((elapsed + 5))                                  # 只累加 5，不计入 curl 实际耗时
  done
}
```

`elapsed` 只累加固定的 `5`（sleep），未计入 `curl -m 35` 的实际阻塞时间。当被等待端点 hang（如 db 故障下 readiness 端点因 db health indicator 连接超时而阻塞，返回 HTTP 000 而非 503），每次循环实际耗时 ≈ 35s（curl）+ 5s（sleep）= 40s，而 `elapsed` 仅 +5。要达到 `max_wait=120` 需 24 次循环 ≈ 16 分钟，远超声明的 120s。同类 `wait_for_healthy`/`wait_for_unhealthy`/`wait_for_health_endpoint` 若存在相同模式一并核验。

**目标**：让 `wait_for_http_status`（及同模式轮询函数）的实际墙钟等待时间不超过声明的 `max_wait` 上限，使 `docker_readiness_test.sh` 测试 6 在端点 hang 时按预期在 ~120s 内超时 FAIL 并继续后续恢复步骤。

## 范围

- 修正 `wait_for_http_status` 的计时逻辑：用墙钟时间（`date +%s`）累计实际耗时，或让 `elapsed` 计入 `curl` 实际耗时，使总等待时间受 `max_wait` 真实约束。
- 核验并按需修正同文件其他同模式轮询函数（`wait_for_healthy` / `wait_for_unhealthy` / `wait_for_health_endpoint`）。
- 补充脚本级测试覆盖「端点持续 hang 时在 max_wait 内退出」的行为。

## 非目标

- 不修复 db 故障下 readiness 返回 HTTP 000 而非 503 的根因（属运行时 readiness 行为，另行评估，不在本脚本 Hotfix 范围）。
- 不改 `docker_readiness_test.sh` 的测试用例集与断言。
- 不改其他 HTTP 回归脚本的业务断言。

## 前置依赖

- **执行前置 Plan**：`plan_10`
- 所属主 Plan 已完成。

## 文件边界

- `scripts/tests/http/docker_readiness_test.sh`
- `scripts/tests/`（若新增脚本级测试）

## 关联范围与规模说明

- 仅涉及测试基础设施（bash 轮询函数），1 个业务模块、1 个有效任务，不升级为主 Plan。
- 与 `plan_6.hotfix_7`（检索召回）独立，无执行依赖。

## 关键决策

- 优先用 `date +%s` 计算墙钟 `elapsed`，简单可靠、与 `curl -m` 实际耗时一致；保留 `sleep` 间隔与 `curl -m` 超时不变。
- 修正只改计时累加，不改轮询语义（仍轮询直到命中或超时）。

## 未决问题

- 无。

## 风险与回滚

- **风险**：计时修正后某些原本靠「误判未超时」才勉强通过的路径可能更易暴露真实 FAIL。预防：这正是期望行为，失败用例单独跟踪。回滚：revert 计时改动。
- **风险**：`date +%s` 在容器/CI 环境差异。预防：macOS/Linux 均支持 `date +%s`。回滚：恢复 `elapsed += 5`。

## 测试与验证计划

- 脚本语法：`bash -n scripts/tests/http/docker_readiness_test.sh`。
- 行为验证：在 db 故障（readiness hang）场景下运行 `docker_readiness_test.sh`，确认测试 6 在 `max_wait`（约 120s）内超时并继续恢复步骤，而非卡 ~16 分钟。
- 完整回归：`docker compose up -d --build` + `bash scripts/tests/http/docker_readiness_test.sh` 全流程 PASS（或按预期在受控时间内 FAIL 并恢复）。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 10.hotfix_1.1 | wait_for_http_status 墙钟计时修正与同类函数核验 | ⏳ 待开始 | — | — |

整体进度：0 / 1（0%）

## 10.hotfix_1.1 wait_for_http_status 墙钟计时修正与同类函数核验

**目标**：使 `wait_for_http_status` 的实际等待时间受 `max_wait` 真实约束。
**前置任务**：无
**范围**：改用 `date +%s` 累计墙钟 `elapsed`（或等价方案计入 curl 实际耗时）；核验 `wait_for_healthy` / `wait_for_unhealthy` / `wait_for_health_endpoint` 是否同模式并按需修正；补充脚本级 hang 超时测试。
**非目标**：不改测试用例与断言；不改 readiness 运行时行为。
**验收标准**：db 故障 hang 场景下测试 6 在 `max_wait` 内超时并继续恢复；`bash -n` 通过；完整 `docker_readiness_test.sh` 不因计时修正误 FAIL。
**验证方式**：`bash -n scripts/tests/http/docker_readiness_test.sh`；`docker compose up -d --build` + `bash scripts/tests/http/docker_readiness_test.sh`。
**涉及文件**：`scripts/tests/http/docker_readiness_test.sh`、可能的 `scripts/tests/` 新增测试

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/docker_readiness_test.sh`（测试 6） | 缺陷 | 测试 6 卡在 `等待 readiness 503` 约 6 分钟未推进；分析 `wait_for_http_status` 源码确认 `elapsed` 未计入 `curl -m 35` 耗时，实际超时需 ~16 分钟 |

## 阻塞记录

无。本 Hotfix 为非优先项，登记后等待闲时执行；当前不阻塞任何 Plan。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_15 第四次独立验收 Docker HTTP 重跑发现 docker_readiness_test.sh 测试 6 因 `wait_for_http_status` 计时 bug 长时间卡住 | 初始范围；状态 ready，非优先闲时修复 |
