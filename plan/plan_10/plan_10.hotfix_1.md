---
workflow_version: 3
plan_id: plan_10.hotfix_1
type: hotfix
parent_plan: plan_10
status: completed
created: 2026-06-25
updated: 2026-06-30
---

# plan_10.hotfix_1 — Docker readiness 轮询 deadline 修正

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

Plan 21.11 已将 Smoke 拓扑收口为启用 `CRAG_SERVICE_PROFILES=smoke` 的原 `rag-service`，并调整了同一脚本的服务拓扑与端口；上述轮询函数及计时缺陷未被改动，根因仍成立。

**目标**：让 `wait_for_http_status`（及同模式轮询函数）以真实 deadline 约束墙钟等待时间，使 `docker_readiness_test.sh` 测试 6 在端点 hang 时于 `max_wait` 附近超时并继续后续恢复步骤，不再因每轮 `curl` timeout 成倍延长到分钟级。

## 范围

- 将等待逻辑提取为可单独 source 的 shell helper，统一以 `deadline = start + max_wait` 计算剩余时间。
- 修正 `wait_for_http_status` 与 `wait_for_health_endpoint`：每轮 `curl` timeout 和 sleep 均不得超过剩余时间。
- 让 `wait_for_healthy` / `wait_for_unhealthy` 使用相同的墙钟 elapsed 与 deadline 口径；保留现有 Docker 状态判定语义。
- 补充脚本级 deadline 回归：用本地可控 hang HTTP endpoint 证明等待不会因单次请求 timeout 成倍超出 `max_wait`。

## 非目标

- 不修复 db 故障下 readiness 返回 HTTP 000 而非 503 的根因（属运行时 readiness 行为，另行评估，不在本脚本 Hotfix 范围）。
- 不改 `docker_readiness_test.sh` 的测试用例集与断言。
- 不改其他 HTTP 回归脚本的业务断言。
- 不引入第三方 shell 测试框架。

## 前置依赖

- **执行前置 Plan**：`plan_10`
- 所属主 Plan 已完成。

## 文件边界

- `scripts/tests/http/docker_readiness_test.sh`
- `scripts/tests/http/lib/wait_helpers.sh`（新增）
- `scripts/tests/test_wait_helpers.sh`（新增）

## 关联范围与规模说明

- 仅涉及测试基础设施（bash 轮询函数），1 个业务模块、1 个有效任务，不升级为主 Plan。
- 与 `plan_6.hotfix_7`（检索召回）独立，无执行依赖。

## 关键决策

- 使用 `date +%s` 计算绝对 deadline 与真实 elapsed；macOS/Linux 均支持该接口。
- `curl` 使用 `min(原 timeout, 剩余秒数)`，sleep 使用 `min(5, 剩余秒数)`；不得在 deadline 前启动一个完整 35 秒请求后无条件等待结束。
- 允许 shell 调度和命令启动造成少量秒级误差；验收重点是消除随循环次数累积的分钟级放大，不承诺毫秒级硬实时。
- helper 只封装轮询机制，不承载测试步骤、服务名、URL 或业务断言。

## 未决问题

- 无。

## 风险与回滚

- **风险**：计时修正后某些原本靠「误判未超时」才勉强通过的路径可能更快暴露真实 FAIL。预防：保留原业务断言和恢复流程，失败按真实 readiness 问题单独跟踪。回滚：revert helper 接入提交。
- **风险**：剩余时间小于 1 秒时 curl 参数非法或形成忙循环。预防：每轮先判断剩余整秒；小于 1 时直接超时，不再发请求。
- **风险**：helper 提取改变现有函数调用方式。预防：保持四个公开 shell 函数的参数顺序和返回码不变，`docker_readiness_test.sh` 只新增 source。回滚：把函数内联回原脚本。

## 测试与验证计划

- 脚本语法：`bash -n scripts/tests/http/docker_readiness_test.sh`。
- helper 与测试脚本语法：`bash -n scripts/tests/http/lib/wait_helpers.sh scripts/tests/test_wait_helpers.sh`。
- 定向行为测试：`bash scripts/tests/test_wait_helpers.sh`，以短 `max_wait` 调用持续 hang endpoint；期望非零返回且实际耗时仅允许少量秒级调度误差。
- 完整回归：`bash scripts/tests/http/docker_readiness_test.sh`。期望全流程 PASS；若当前运行时仍让 readiness 返回 HTTP 000，则测试 6 必须在约 120 秒内记录 FAIL、恢复 db 并完成清理，不得卡住约 16 分钟。该运行时失败需要另行登记，不能把受控退出表述为完整回归通过。

## 进度追踪

| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 10.hotfix_1.1 | 提取 deadline 轮询 helper 并修正 readiness 回归计时 | ✅ 完成 | 4f4b535 | 2026-06-30 |

整体进度：1 / 1（100%）

## 10.hotfix_1.1 提取 deadline 轮询 helper 并修正 readiness 回归计时

**目标**：以可测试的 deadline helper 约束四类轮询函数的真实墙钟耗时，消除 `curl` timeout 未计入等待上限导致的分钟级放大。
**前置任务**：无
**范围**：新增 `wait_helpers.sh`，保持 `wait_for_healthy(service,max_wait)`、`wait_for_unhealthy(service,max_wait)`、`wait_for_http_status(url,expected,desc,max_wait)`、`wait_for_health_endpoint(port,path,expected,desc,max_wait,curl_timeout)` 的调用接口；内部以绝对 deadline 计算真实 elapsed，将 curl/sleep 裁剪到剩余时间；`docker_readiness_test.sh` 删除内联实现并 source helper；新增 `test_wait_helpers.sh` 启动可控 hang endpoint，以短 deadline 断言 helper 返回非零且不发生分钟级超时。
**非目标**：不改测试 1–7 的业务步骤与断言；不改 readiness 运行时、Compose healthcheck 或其他 HTTP 回归脚本；不引入第三方测试框架。
**验收标准**：三份 shell 文件 `bash -n` 通过；定向 helper 测试稳定证明 hang 请求受 deadline 控制；四个函数参数与返回语义保持兼容；完整 readiness 回归通过，或在既有 HTTP 000 运行时缺陷下于约 120 秒内失败并确认 db 恢复和 Compose 清理完成，不再卡约 16 分钟。
**验证方式**：`bash -n scripts/tests/http/docker_readiness_test.sh scripts/tests/http/lib/wait_helpers.sh scripts/tests/test_wait_helpers.sh`；`bash scripts/tests/test_wait_helpers.sh`；`bash scripts/tests/http/docker_readiness_test.sh`；失败场景检查脚本输出包含测试 6 超时、恢复 db 与最终清理阶段。
**涉及文件**：`scripts/tests/http/docker_readiness_test.sh`、`scripts/tests/http/lib/wait_helpers.sh`、`scripts/tests/test_wait_helpers.sh`

## 验收记录

| 日期 | 环境 | 命令或检查 | 结果 | 摘要 |
| --- | --- | --- | --- | --- |
| 2026-06-25 | macOS, Docker | `bash scripts/tests/http/docker_readiness_test.sh`（测试 6） | 缺陷 | 测试 6 卡在 `等待 readiness 503` 约 6 分钟未推进；分析 `wait_for_http_status` 源码确认 `elapsed` 未计入 `curl -m 35` 耗时，实际超时需 ~16 分钟 |
| 2026-06-30 | macOS, bash | `bash -n scripts/tests/http/docker_readiness_test.sh scripts/tests/http/lib/wait_helpers.sh scripts/tests/test_wait_helpers.sh` | ✅ 通过 | 三份 shell 文件语法均通过 |
| 2026-06-30 | macOS, bash | `bash scripts/tests/test_wait_helpers.sh` | ✅ 通过 | 本地可控 hang 端点证明 deadline 生效：`wait_for_http_status` 与 `wait_for_health_endpoint` 在 hang 端点下均返回非零，max_wait=8 实际耗时 8s（上限 13s）；原缺陷下同条件约 80s+。正向用例即时返回 200。helper 四函数签名与返回码保持兼容。 |
| 2026-06-30 | macOS, Docker Compose | `bash scripts/tests/http/docker_readiness_test.sh` | ❌ 受控失败（符合 验证方式 预期的失败场景） | 核心 deadline 修复生效：测试 6 `rag-service readiness 已返回 HTTP 503（31s）`（≤120s 上限，不再卡 ~16 分钟）；liveness 0s 返回 200；db 恢复（5s healthy）、rag-service 恢复（0s）、Compose 清理均完成。三项预存运行时失败不在本 hotfix 范围、断言与 `check_*` 未改：①测试 3 `open-api /actuator/health` 聚合端点 503（其 readiness 为 200）；②测试 6 容器在 150s 内未翻转为 unhealthy；③测试 7 写入文档 down/up 后只读 SQL 查询为 0。建议另行登记为运行时/可观测性缺陷。 |
| 2026-06-30 | macOS, bash | `bash -n scripts/tests/http/docker_readiness_test.sh scripts/tests/http/lib/wait_helpers.sh scripts/tests/test_wait_helpers.sh` | ✅ 通过 | 独立验收 session 重跑；三份 shell 文件语法均通过 |
| 2026-06-30 | macOS, bash | `bash scripts/tests/test_wait_helpers.sh` | ✅ 通过 | 独立验收 session 重跑；本地可控 hang 端点证明 deadline 生效：`wait_for_http_status` 与 `wait_for_health_endpoint`（curl_timeout=35）在 hang 端点下均返回非零、max_wait=8 实际耗时 8s（上限 13s），原缺陷下同条件 ~80s+；正向 200 即时返回。四函数签名与返回码保持兼容。 |
| 2026-06-30 | macOS, Docker 29.5.2 / Compose v5.1.4 | `bash scripts/tests/http/docker_readiness_test.sh`（首次因 Docker Hub registry `Service Unavailable` 拉取 `eclipse-temurin:21-jdk` 于测试 4 中止；registry 恢复后独立重跑全程） | ❌ 受控失败（符合 验证方式 预期的失败场景） | 独立验收 session 重跑确认核心 deadline 修复在真实 Docker 下生效：测试 6 `rag-service readiness 已返回 HTTP 503（30s）`（≤120s 上限，不再卡 ~16 分钟）、liveness 200（0s）；`wait_for_unhealthy` 正确以 150s 为界报告失败未挂起；db 恢复（5s healthy）、rag-service 恢复（0s）、最终 `docker compose down` 清理完成。三项预存运行时失败复现且在本 hotfix 范围外、断言与 `check_*` 未改：①测试 3 `open-api /actuator/health` 聚合端点 503（其 readiness 为 200）；②测试 6 容器在 150s 内未翻转为 unhealthy；③测试 7 写入文档 down/up 后只读 SQL 查询为 0。建议另行登记为运行时/可观测性缺陷。 |
| 2026-06-30 | macOS | `python3 scripts/validate_plans.py --strict --verify-git` | ✅ 通过 | 独立验收 session 执行；0 error，24 warning 均为历史 Plan 未使用 workflow v3 的 P101（允许），`--verify-git` 未报实现 hash 缺失 |

### 独立验收结论（2026-06-30）

验收 session 未参与实现，从仓库事实重建上下文。任务 10.hotfix_1.1 四项验收标准全部满足：

1. 三份 shell 文件 `bash -n` 通过（独立重跑）。
2. 定向 helper 测试稳定证明 hang 请求受 deadline 控制（独立重跑 `test_wait_helpers.sh`，hang 端点耗时 8s ≤ 13s 上限，原缺陷 ~80s+）。
3. 四个函数（`wait_for_healthy`/`wait_for_unhealthy`/`wait_for_http_status`/`wait_for_health_endpoint`）参数顺序与返回码保持兼容，`docker_readiness_test.sh` 四处调用签名匹配，`check_*` 单次函数与测试 1–7 断言未改（代码审查 + `git show --stat 4f4b535` 范围核验，仅涉及声明的三份文件）。
4. 完整 readiness 回归按 验证方式 预期受控失败：测试 6 readiness 503（30s，≤120s）、db 恢复与 Compose 清理完成，不再卡 ~16 分钟；整体回归失败由三项预存、范围外运行时缺陷导致（open-api 聚合健康 503、容器 unhealthy 翻转时序、down/up 后只读查询为 0），不构成本 hotfix 阻塞项，建议另行登记。

任务标记完成，Hotfix 转为 `completed`。

## 阻塞记录

无。本 Hotfix 为非优先项，登记后等待闲时执行；当前不阻塞任何 Plan。

## 废弃任务记录

无。

## 变更记录

| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-25 | 创建 Hotfix | plan_15 第四次独立验收 Docker HTTP 重跑发现 docker_readiness_test.sh 测试 6 因 `wait_for_http_status` 计时 bug 长时间卡住 | 初始范围；状态 ready，非优先闲时修复 |
| 2026-06-30 | 按当前落地校准实现方案 | Plan 21.11 已调整服务拓扑但保留原计时缺陷；单纯更新 elapsed 仍可能因固定 curl timeout 越过上限 | 状态保持 ready；改为可测试的 deadline helper，明确受控失败不等于完整回归通过 |
| 2026-06-30 | 实现并交接验收 | deadline helper 提取完成、helper 定向测试通过、完整回归受控失败（核心计时修复已证明） | 实现提交 4f4b535；任务转 待验收，Plan 转 verifying；三项预存运行时失败另行登记 |
| 2026-06-30 | 独立验收通过 | 验收 session 独立重跑 `bash -n`、`test_wait_helpers.sh`、完整 Docker readiness 回归与 Plan 严格校验，四项验收标准全部满足 | 任务转 完成，Plan 转 completed；同步 plan/index/README.md 索引与验收队列 |
