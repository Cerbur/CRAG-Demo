import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "validate_plans.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_plans", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VALID_PLAN = """\
---
workflow_version: 3
plan_id: plan_9
type: main
status: ready
created: 2026-06-19
updated: 2026-06-19
---

# plan_9 — Test

## 背景与目标
Goal.
## 范围
Scope.
## 非目标
No.
## 前置依赖
- **执行前置 Plan**：无
None.
## 文件边界
`scripts/**`
## 关键决策
Decision.
## 未决问题
None.
## 风险与回滚
Revert the commit.
## 测试与验证计划
Run tests.
## 进度追踪
| 编号 | 任务 | 状态 | 提交 | 完成时间 |
| --- | --- | --- | --- | --- |
| 9.1 | Validate | ⏳ 待开始 | — | — |

整体进度：0 / 1（0%）

## 9.1 Validate
**目标**：Result.
**前置任务**：无
**范围**：Validator.
**非目标**：Runtime.
**验收标准**：Pass.
**验证方式**：Run unittest.
**涉及文件**：`scripts/**`
## 验收记录
None.
## 阻塞记录
None.
## 废弃任务记录
None.
## 变更记录
| 日期 | 变更 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-06-19 | Create | Need | Initial |
"""

BLOCKED_RECORD = """\
- **日期**：2026-06-19
- **原因**：等待前置计划。
- **当前进度**：任务尚未开始。
- **解除条件**：前置计划完成。
- **解除方**：前置计划 owner。
- **恢复后的下一步**：重新校准后继续。
"""


class ValidatePlansTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def validate(self, content, verify_git=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan_9.md"
            path.write_text(content, encoding="utf-8")
            return self.validator.validate_plan_file(path, strict=True, verify_git=verify_git)

    def test_accepts_valid_ready_plan(self):
        diagnostics = self.validate(VALID_PLAN)
        self.assertEqual([], [item for item in diagnostics if item.level == "ERROR"])

    def test_rejects_owner_in_v3_plan(self):
        content = VALID_PLAN.replace("status: ready", "status: ready\nowner: developer")
        diagnostics = self.validate(content)
        self.assertIn("P208", {item.rule for item in diagnostics})

    def test_accepts_verifying_plan_with_real_implementation_hash(self):
        content = VALID_PLAN.replace("status: ready", "status: verifying").replace(
            "| 9.1 | Validate | ⏳ 待开始 | — | — |",
            "| 9.1 | Validate | 🔍 待验收 | deadbee | — |",
        )
        diagnostics = self.validate(content)
        self.assertEqual([], [item for item in diagnostics if item.level == "ERROR"])

    def test_rejects_verifying_task_with_pending_commit(self):
        content = VALID_PLAN.replace("status: ready", "status: verifying").replace(
            "| 9.1 | Validate | ⏳ 待开始 | — | — |",
            "| 9.1 | Validate | 🔍 待验收 | pending | — |",
        )
        diagnostics = self.validate(content)
        self.assertIn("P214", {item.rule for item in diagnostics})

    def test_rejects_verifying_plan_with_in_progress_task(self):
        content = VALID_PLAN.replace("status: ready", "status: verifying").replace(
            "| 9.1 | Validate | ⏳ 待开始 | — | — |",
            "| 9.1 | Validate | 🚧 进行中 | — | — |",
        )
        diagnostics = self.validate(content)
        self.assertIn("P224", {item.rule for item in diagnostics})

    def test_rejects_completed_task_without_commit_hash(self):
        content = VALID_PLAN.replace("status: ready", "status: in_progress").replace(
            "| 9.1 | Validate | ⏳ 待开始 | — | — |",
            "| 9.1 | Validate | ✅ 完成 | — | 2026-06-19 |",
        ).replace("整体进度：0 / 1（0%）", "整体进度：1 / 1（100%）")
        diagnostics = self.validate(content)
        self.assertIn("P216", {item.rule for item in diagnostics})

    def test_rejects_progress_mismatch(self):
        diagnostics = self.validate(
            VALID_PLAN.replace("整体进度：0 / 1（0%）", "整体进度：1 / 1（100%）")
        )
        self.assertIn("P220", {item.rule for item in diagnostics})

    def test_verify_git_rejects_unknown_hash(self):
        content = VALID_PLAN.replace("status: ready", "status: completed").replace(
            "| 9.1 | Validate | ⏳ 待开始 | — | — |",
            "| 9.1 | Validate | ✅ 完成 | deadbee | 2026-06-19 |",
        ).replace("整体进度：0 / 1（0%）", "整体进度：1 / 1（100%）")
        diagnostics = self.validate(content, verify_git=True)
        self.assertIn("P218", {item.rule for item in diagnostics})

    def test_index_rejects_v3_status_or_progress_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_path = root / "plan/plan_9/plan_9.md"
            plan_path.parent.mkdir(parents=True)
            plan_path.write_text(VALID_PLAN, encoding="utf-8")
            index_path = root / "plan/index/README.md"
            index_path.parent.mkdir(parents=True)
            index_path.write_text(
                "| Plan | 主要功能 | 状态 | 活跃修正 | 入口 |\n"
                "| --- | --- | --- | --- | --- |\n"
                "| plan_9 | Test | ✅ 完成 (1/1) | — | [plan_9.md](../plan_9/plan_9.md) |\n",
                encoding="utf-8",
            )
            diagnostics = self.validator.validate_index(root, [plan_path])
        self.assertIn("P304", {item.rule for item in diagnostics})

    def test_repository_requires_all_plan_templates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "plan/index").mkdir(parents=True)
            (root / "plan/index/README.md").write_text("# Index\n", encoding="utf-8")
            diagnostics = self.validator.validate_repository(root, [], strict=True, verify_git=False)
        self.assertIn("P401", {item.rule for item in diagnostics})

    def test_blocked_plan_requires_complete_block_record(self):
        content = VALID_PLAN.replace("status: ready", "status: blocked").replace(
            "## 阻塞记录\nNone.",
            "## 阻塞记录\n- **原因**：等待前置计划。",
        )
        diagnostics = self.validate(content)
        self.assertIn("P222", {item.rule for item in diagnostics})

    def test_blocked_plan_accepts_complete_block_record(self):
        content = VALID_PLAN.replace("status: ready", "status: blocked").replace(
            "## 阻塞记录\nNone.",
            f"## 阻塞记录\n{BLOCKED_RECORD}",
        )
        diagnostics = self.validate(content)
        self.assertNotIn("P222", {item.rule for item in diagnostics})

    def test_repository_rejects_plan_dependency_cycle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_9 = self.write_plan(
                root,
                "plan_9",
                VALID_PLAN.replace("None.", "- **执行前置 Plan**：`plan_10`", 1),
            )
            plan_10_content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_10").replace(
                "# plan_9", "# plan_10"
            ).replace("| 9.1 |", "| 10.1 |").replace("## 9.1", "## 10.1").replace(
                "None.", "- **执行前置 Plan**：`plan_9`", 1
            )
            plan_10 = self.write_plan(root, "plan_10", plan_10_content)
            self.write_index(
                root,
                [("plan_9", "待开始", "0/1"), ("plan_10", "待开始", "0/1")],
                "plan_9 → plan_10",
            )
            diagnostics = self.validator.validate_dependencies(root, [plan_9, plan_10])
        self.assertIn("P305", {item.rule for item in diagnostics})

    def test_index_rejects_execution_queue_that_violates_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_9 = self.write_plan(root, "plan_9", VALID_PLAN)
            plan_10_content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_10").replace(
                "# plan_9", "# plan_10"
            ).replace("| 9.1 |", "| 10.1 |").replace("## 9.1", "## 10.1").replace(
                "None.", "- **执行前置 Plan**：`plan_9`", 1
            )
            plan_10 = self.write_plan(root, "plan_10", plan_10_content)
            self.write_index(
                root,
                [("plan_9", "待开始", "0/1"), ("plan_10", "待开始", "0/1")],
                "plan_10 → plan_9",
            )
            diagnostics = self.validator.validate_index(root, [plan_9, plan_10])
        self.assertIn("P306", {item.rule for item in diagnostics})

    def test_index_rejects_missing_active_plan_from_execution_queue(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_9 = self.write_plan(root, "plan_9", VALID_PLAN)
            plan_10_content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_10").replace(
                "# plan_9", "# plan_10"
            ).replace("| 9.1 |", "| 10.1 |").replace("## 9.1", "## 10.1")
            plan_10 = self.write_plan(root, "plan_10", plan_10_content)
            self.write_index(
                root,
                [("plan_9", "待开始", "0/1"), ("plan_10", "待开始", "0/1")],
                "plan_9",
            )
            diagnostics = self.validator.validate_index(root, [plan_9, plan_10])
        self.assertIn("P306", {item.rule for item in diagnostics})

    def test_index_accepts_separate_execution_and_acceptance_queues(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_9 = self.write_plan(root, "plan_9", VALID_PLAN)
            verifying_content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_10").replace(
                "# plan_9", "# plan_10"
            ).replace("| 9.1 |", "| 10.1 |").replace("## 9.1", "## 10.1").replace(
                "status: ready", "status: verifying"
            ).replace(
                "| 10.1 | Validate | ⏳ 待开始 | — | — |",
                "| 10.1 | Validate | 🔍 待验收 | deadbee | — |",
            )
            plan_10 = self.write_plan(root, "plan_10", verifying_content)
            self.write_index(
                root,
                [("plan_9", "待开始", "0/1"), ("plan_10", "待验收", "0/1")],
                "plan_9",
                acceptance_queue="plan_10",
            )
            diagnostics = self.validator.validate_index(root, [plan_9, plan_10])
        self.assertNotIn("P306", {item.rule for item in diagnostics})
        self.assertNotIn("P307", {item.rule for item in diagnostics})

    def test_index_rejects_execution_when_dependency_is_still_verifying(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            verifying_content = VALID_PLAN.replace("status: ready", "status: verifying").replace(
                "| 9.1 | Validate | ⏳ 待开始 | — | — |",
                "| 9.1 | Validate | 🔍 待验收 | deadbee | — |",
            )
            plan_9 = self.write_plan(root, "plan_9", verifying_content)
            plan_10_content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_10").replace(
                "# plan_9", "# plan_10"
            ).replace("| 9.1 |", "| 10.1 |").replace("## 9.1", "## 10.1").replace(
                "None.", "- **执行前置 Plan**：`plan_9`", 1
            )
            plan_10 = self.write_plan(root, "plan_10", plan_10_content)
            self.write_index(
                root,
                [("plan_9", "待验收", "0/1"), ("plan_10", "待开始", "0/1")],
                "plan_10",
                acceptance_queue="plan_9",
            )
            diagnostics = self.validator.validate_index(root, [plan_9, plan_10])
        self.assertIn("P306", {item.rule for item in diagnostics})

    def test_index_rejects_execution_when_dependency_is_abandoned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            abandoned_content = VALID_PLAN.replace("status: ready", "status: abandoned").replace(
                "| 9.1 | Validate | ⏳ 待开始 | — | — |",
                "| 9.1 | Validate | 🗑️ 废弃 | — | — |",
            ).replace("整体进度：0 / 1（0%）", "整体进度：0 / 0（0%），废弃：1").replace(
                "## 废弃任务记录\nNone.", "## 废弃任务记录\nAbandoned by decision."
            )
            plan_9 = self.write_plan(root, "plan_9", abandoned_content)
            plan_10_content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_10").replace(
                "# plan_9", "# plan_10"
            ).replace("| 9.1 |", "| 10.1 |").replace("## 9.1", "## 10.1").replace(
                "None.", "- **执行前置 Plan**：`plan_9`", 1
            )
            plan_10 = self.write_plan(root, "plan_10", plan_10_content)
            self.write_index(
                root,
                [("plan_9", "废弃", "0/0"), ("plan_10", "待开始", "0/1")],
                "plan_10",
            )
            diagnostics = self.validator.validate_index(root, [plan_9, plan_10])
        self.assertIn("P306", {item.rule for item in diagnostics})

    def test_index_rejects_verifying_plan_in_execution_queue(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = VALID_PLAN.replace("status: ready", "status: verifying").replace(
                "| 9.1 | Validate | ⏳ 待开始 | — | — |",
                "| 9.1 | Validate | 🔍 待验收 | deadbee | — |",
            )
            plan_9 = self.write_plan(root, "plan_9", content)
            self.write_index(
                root,
                [("plan_9", "待验收", "0/1")],
                "plan_9",
                acceptance_queue="无",
            )
            diagnostics = self.validator.validate_index(root, [plan_9])
        self.assertIn("P306", {item.rule for item in diagnostics})
        self.assertIn("P307", {item.rule for item in diagnostics})

    def test_index_rejects_hotfix_status_or_progress_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = VALID_PLAN.replace("plan_id: plan_9", "plan_id: plan_8.hotfix_1").replace(
                "type: main", "type: hotfix\nparent_plan: plan_8"
            ).replace("# plan_9", "# plan_8.hotfix_1").replace(
                "| 9.1 |", "| 8.hotfix_1.1 |"
            ).replace("## 9.1", "## 8.hotfix_1.1")
            plan_path = root / "plan/plan_8/plan_8.hotfix_1.md"
            plan_path.parent.mkdir(parents=True)
            plan_path.write_text(content, encoding="utf-8")
            self.write_index(
                root,
                [("plan_8.hotfix_1", "完成", "1/1")],
                "plan_8.hotfix_1",
                hotfix=True,
            )
            diagnostics = self.validator.validate_index(root, [plan_path])
        self.assertIn("P304", {item.rule for item in diagnostics})

    def write_plan(self, root, plan_id, content):
        path = root / f"plan/{plan_id}/{plan_id}.md"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_index(self, root, rows, queue, hotfix=False, acceptance_queue="无"):
        index_path = root / "plan/index/README.md"
        index_path.parent.mkdir(parents=True, exist_ok=True)
        table_rows = []
        for plan_id, status, progress in rows:
            link = f"../{plan_id}/{plan_id}.md"
            table_rows.append(f"| {plan_id} | Test | {status} ({progress}) | — | [{plan_id}.md]({link}) |")
        heading = "Plan_8 明细" if hotfix else "主计划索引"
        index_path.write_text(
            f"## {heading}\n\n"
            "| Plan | 主要功能 | 状态 | 活跃修正 | 入口 |\n"
            "| --- | --- | --- | --- | --- |\n"
            + "\n".join(table_rows)
            + "\n\n## 当前执行队列\n\n"
            + f"```text\n{queue}\n```\n"
            + "\n## 当前验收队列\n\n"
            + f"```text\n{acceptance_queue}\n```\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
