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
workflow_version: 2
plan_id: plan_9
type: main
status: ready
owner: parent-agent
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


if __name__ == "__main__":
    unittest.main()
