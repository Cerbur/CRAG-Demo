#!/usr/bin/env python3
"""Validate CRAG-Demo Plan documents without third-party dependencies."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import NamedTuple


PLAN_FILE_RE = re.compile(r"plan_(\d+)(?:\.hotfix_(\d+))?\.md$")
PLAN_ID_RE = re.compile(r"plan_\d+(?:\.hotfix_\d+)?")
DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}$")
HASH_RE = re.compile(r"[0-9a-f]{7,40}$")
PROGRESS_RE = re.compile(r"整体进度：(\d+) / (\d+)（(\d+)%）(?:，废弃：(\d+))?")
TASK_ROW_RE = re.compile(
    r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|$"
)

PLAN_STATUSES = {
    "draft",
    "ready",
    "in_progress",
    "verifying",
    "blocked",
    "completed",
    "abandoned",
}
PLAN_STATUS_LABELS = {
    "draft": "草稿",
    "ready": "待开始",
    "in_progress": "进行中",
    "verifying": "待验收",
    "blocked": "阻塞",
    "completed": "完成",
    "abandoned": "废弃",
}
TASK_STATUS_BY_LABEL = {
    "待开始": "pending",
    "进行中": "in_progress",
    "待验收": "verifying",
    "阻塞": "blocked",
    "完成": "completed",
    "废弃": "abandoned",
}
REQUIRED_SECTIONS = [
    "背景与目标",
    "范围",
    "非目标",
    "前置依赖",
    "文件边界",
    "关键决策",
    "未决问题",
    "风险与回滚",
    "测试与验证计划",
    "进度追踪",
    "验收记录",
    "阻塞记录",
    "废弃任务记录",
    "变更记录",
]
TASK_FIELDS = ["目标", "前置任务", "范围", "非目标", "验收标准", "验证方式", "涉及文件"]
BLOCK_RECORD_FIELDS = ["日期", "原因", "当前进度", "解除条件", "解除方", "恢复后的下一步"]


class Diagnostic(NamedTuple):
    level: str
    rule: str
    path: Path
    message: str


class Task(NamedTuple):
    task_id: str
    status: str
    commits: str
    completed_at: str


def diagnostic(level: str, rule: str, path: Path, message: str) -> Diagnostic:
    return Diagnostic(level, rule, path, message)


def parse_front_matter(text: str, path: Path) -> tuple[dict[str, str], str, list[Diagnostic]]:
    if not text.startswith("---\n"):
        return {}, text, []
    end = text.find("\n---\n", 4)
    if end < 0:
        return {}, text, [diagnostic("ERROR", "P201", path, "front matter 缺少结束分隔符")]
    metadata: dict[str, str] = {}
    issues: list[Diagnostic] = []
    for raw_line in text[4:end].splitlines():
        if not raw_line or ":" not in raw_line:
            issues.append(diagnostic("ERROR", "P202", path, f"非法 front matter 行：{raw_line!r}"))
            continue
        key, value = raw_line.split(":", 1)
        key, value = key.strip(), value.strip()
        if not key or not value or any(token in value for token in ("[", "]", "{", "}", "|", ">")):
            issues.append(diagnostic("ERROR", "P202", path, f"仅允许简单 key: value：{raw_line!r}"))
            continue
        if key in metadata:
            issues.append(diagnostic("ERROR", "P203", path, f"重复元信息字段：{key}"))
        metadata[key] = value
    return metadata, text[end + 5 :], issues


def parse_tasks(body: str) -> list[Task]:
    tasks: list[Task] = []
    in_table = False
    for line in body.splitlines():
        if line.startswith("| 编号 | 任务 | 状态 | 提交 | 完成时间 |"):
            in_table = True
            continue
        if not in_table:
            continue
        if line.startswith("| ---"):
            continue
        match = TASK_ROW_RE.match(line)
        if not match:
            break
        task_id, _, status_cell, commits, completed_at = (value.strip() for value in match.groups())
        status = next((value for label, value in TASK_STATUS_BY_LABEL.items() if label in status_cell), "")
        tasks.append(Task(task_id, status, commits, completed_at))
    return tasks


def section_content(body: str, heading: str) -> str:
    match = re.search(
        rf"^##\s+{re.escape(heading)}\s*$\n(.*?)(?=^##\s+|\Z)",
        body,
        re.MULTILINE | re.DOTALL,
    )
    return match.group(1).strip() if match else ""


def parse_plan_dependencies(body: str) -> set[str]:
    dependencies: set[str] = set()
    for line in section_content(body, "前置依赖").splitlines():
        if "**执行前置 Plan**：" in line:
            dependencies.update(PLAN_ID_RE.findall(line))
    return dependencies


def parse_queue(index_text: str, heading: str) -> list[str]:
    section = section_content(index_text, heading)
    code_block = re.search(r"```text\s*(.*?)```", section, re.DOTALL)
    return PLAN_ID_RE.findall(code_block.group(1)) if code_block else []


def git_hash_exists(repo_root: Path, value: str) -> bool:
    result = subprocess.run(
        ["git", "cat-file", "-e", f"{value}^{{commit}}"],
        cwd=repo_root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return result.returncode == 0


def validate_plan_file(path: Path, strict: bool = False, verify_git: bool = False) -> list[Diagnostic]:
    text = path.read_text(encoding="utf-8")
    metadata, body, issues = parse_front_matter(text, path)
    match = PLAN_FILE_RE.search(path.name)
    if not match:
        return issues + [diagnostic("ERROR", "P200", path, "执行 Plan 文件名不符合规范")]

    workflow_version = metadata.get("workflow_version")
    if workflow_version != "3":
        level = "ERROR" if workflow_version == "2" and strict else "WARNING"
        issues.append(diagnostic(level, "P101", path, "历史 Plan：未使用 workflow v3"))
        return issues

    required_metadata = {"workflow_version", "plan_id", "type", "status", "created", "updated"}
    if metadata.get("type") == "hotfix":
        required_metadata.add("parent_plan")
    for key in sorted(required_metadata - metadata.keys()):
        issues.append(diagnostic("ERROR", "P204", path, f"缺少元信息字段：{key}"))

    expected_id = f"plan_{match.group(1)}"
    if match.group(2):
        expected_id += f".hotfix_{match.group(2)}"
    if metadata.get("plan_id") != expected_id:
        issues.append(diagnostic("ERROR", "P205", path, f"plan_id 应为 {expected_id}"))
    expected_type = "hotfix" if match.group(2) else "main"
    if metadata.get("type") != expected_type:
        issues.append(diagnostic("ERROR", "P206", path, f"type 应为 {expected_type}"))
    if metadata.get("status") not in PLAN_STATUSES:
        issues.append(diagnostic("ERROR", "P207", path, "Plan status 非法"))
    if "owner" in metadata:
        issues.append(diagnostic("ERROR", "P208", path, "workflow v3 禁止 owner 元信息"))
    for key in ("created", "updated"):
        if key in metadata and not DATE_RE.fullmatch(metadata[key]):
            issues.append(diagnostic("ERROR", "P209", path, f"{key} 必须使用 YYYY-MM-DD"))

    for section in REQUIRED_SECTIONS:
        if not re.search(rf"^##\s+{re.escape(section)}\s*$", body, re.MULTILINE):
            issues.append(diagnostic("ERROR", "P210", path, f"缺少章节：{section}"))
    if (
        metadata.get("status") not in {"completed", "abandoned"}
        and "**执行前置 Plan**：" not in section_content(body, "前置依赖")
    ):
        issues.append(diagnostic("ERROR", "P223", path, "前置依赖缺少“执行前置 Plan”标记"))

    tasks = parse_tasks(body)
    if metadata.get("status") != "draft" and not tasks:
        issues.append(diagnostic("ERROR", "P211", path, "非草稿 Plan 必须至少有一个任务"))

    seen: set[str] = set()
    completed = abandoned = 0
    for task in tasks:
        if task.task_id in seen:
            issues.append(diagnostic("ERROR", "P212", path, f"重复任务编号：{task.task_id}"))
        seen.add(task.task_id)
        if not task.status:
            issues.append(diagnostic("ERROR", "P213", path, f"任务 {task.task_id} 状态非法"))
        if task.status == "completed":
            completed += 1
            hashes = [value.strip() for value in task.commits.split(",")]
            if not hashes or any(not HASH_RE.fullmatch(value) for value in hashes):
                issues.append(diagnostic("ERROR", "P216", path, f"完成任务 {task.task_id} 缺少有效 commit hash"))
            if not DATE_RE.fullmatch(task.completed_at):
                issues.append(diagnostic("ERROR", "P217", path, f"完成任务 {task.task_id} 缺少完成日期"))
            if verify_git:
                repo_root = find_repo_root(path)
                for value in hashes:
                    if HASH_RE.fullmatch(value) and not git_hash_exists(repo_root, value):
                        issues.append(diagnostic("ERROR", "P218", path, f"commit 不存在：{value}"))
        elif task.status == "verifying":
            hashes = [value.strip() for value in task.commits.split(",")]
            if not hashes or any(not HASH_RE.fullmatch(value) for value in hashes):
                issues.append(
                    diagnostic(
                        "ERROR",
                        "P214",
                        path,
                        f"待验收任务 {task.task_id} 必须记录有效实现 commit hash",
                    )
                )
            if task.completed_at not in {"—", "-"}:
                issues.append(diagnostic("ERROR", "P215", path, f"待验收任务 {task.task_id} 不得填写完成日期"))
            if verify_git:
                repo_root = find_repo_root(path)
                for value in hashes:
                    if HASH_RE.fullmatch(value) and not git_hash_exists(repo_root, value):
                        issues.append(diagnostic("ERROR", "P218", path, f"commit 不存在：{value}"))
        else:
            if task.completed_at not in {"—", "-"}:
                issues.append(diagnostic("ERROR", "P215", path, f"未完成任务 {task.task_id} 不得填写完成日期"))
        if task.status == "abandoned":
            abandoned += 1

        detail = re.search(
            rf"^##\s+{re.escape(task.task_id)}\s+.*?(?=^##\s+|\Z)", body, re.MULTILINE | re.DOTALL
        )
        if not detail:
            issues.append(diagnostic("ERROR", "P219", path, f"缺少任务详情：{task.task_id}"))
        elif task.status != "abandoned":
            for field in TASK_FIELDS:
                if f"**{field}**：" not in detail.group(0):
                    issues.append(diagnostic("ERROR", "P219", path, f"任务 {task.task_id} 缺少字段：{field}"))

    progress = PROGRESS_RE.search(body)
    if not progress:
        issues.append(diagnostic("ERROR", "P220", path, "缺少或无法解析整体进度"))
    else:
        shown_completed, shown_total, shown_percent, shown_abandoned = (
            int(value) if value is not None else 0 for value in progress.groups()
        )
        effective = len(tasks) - abandoned
        percent = round(completed * 100 / effective) if effective else 0
        if (shown_completed, shown_total, shown_percent, shown_abandoned) != (
            completed,
            effective,
            percent,
            abandoned,
        ):
            issues.append(diagnostic("ERROR", "P220", path, "整体进度与任务表不一致"))

    if metadata.get("status") == "completed":
        unfinished = [task.task_id for task in tasks if task.status not in {"completed", "abandoned"}]
        if unfinished or not tasks or completed == 0:
            issues.append(diagnostic("ERROR", "P221", path, "完成 Plan 仍有未完成任务或没有有效任务"))

    if metadata.get("status") == "verifying":
        invalid = [
            task.task_id
            for task in tasks
            if task.status not in {"verifying", "completed", "abandoned"}
        ]
        if invalid or not any(task.status == "verifying" for task in tasks):
            issues.append(
                diagnostic(
                    "ERROR",
                    "P224",
                    path,
                    "待验收 Plan 必须至少有一个待验收任务，且其余任务只能为完成或废弃",
                )
            )

    if metadata.get("status") == "blocked":
        blocked = section_content(body, "阻塞记录")
        missing_fields = [
            field for field in BLOCK_RECORD_FIELDS if f"**{field}**：" not in blocked
        ]
        if missing_fields:
            issues.append(
                diagnostic(
                    "ERROR",
                    "P222",
                    path,
                    f"阻塞记录缺少字段：{'、'.join(missing_fields)}",
                )
            )

    return issues


def find_repo_root(path: Path) -> Path:
    current = path.resolve().parent
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return Path.cwd()


def discover_plan_files(repo_root: Path) -> list[Path]:
    return sorted(
        path
        for path in (repo_root / "plan").glob("plan_*/*.md")
        if PLAN_FILE_RE.fullmatch(path.name)
    )


def load_v3_plans(plan_files: list[Path]) -> dict[str, tuple[Path, dict[str, str], str]]:
    plans: dict[str, tuple[Path, dict[str, str], str]] = {}
    for path in plan_files:
        metadata, body, _ = parse_front_matter(path.read_text(encoding="utf-8"), path)
        if metadata.get("workflow_version") == "3" and metadata.get("plan_id"):
            plans[metadata["plan_id"]] = (path, metadata, body)
    return plans


def validate_dependencies(repo_root: Path, plan_files: list[Path]) -> list[Diagnostic]:
    plans = load_v3_plans(plan_files)
    graph = {
        plan_id: {dependency for dependency in parse_plan_dependencies(body) if dependency in plans}
        for plan_id, (_, _, body) in plans.items()
    }
    issues: list[Diagnostic] = []
    visiting: list[str] = []
    visited: set[str] = set()

    def visit(plan_id: str) -> None:
        if plan_id in visiting:
            start = visiting.index(plan_id)
            cycle = visiting[start:] + [plan_id]
            path = plans[plan_id][0]
            issues.append(
                diagnostic("ERROR", "P305", path, f"Plan 前置依赖形成环：{' → '.join(cycle)}")
            )
            return
        if plan_id in visited:
            return
        visiting.append(plan_id)
        for dependency in sorted(graph[plan_id]):
            visit(dependency)
        visiting.pop()
        visited.add(plan_id)

    for plan_id in sorted(graph):
        visit(plan_id)
    return issues


def validate_index(repo_root: Path, plan_files: list[Path]) -> list[Diagnostic]:
    index_path = repo_root / "plan/index/README.md"
    if not index_path.exists():
        return [diagnostic("ERROR", "P301", index_path, "缺少 Plan 索引")]
    text = index_path.read_text(encoding="utf-8")
    issues: list[Diagnostic] = []
    for path in plan_files:
        relative = path.relative_to(repo_root / "plan")
        expected_link = f"../{relative.as_posix()}"
        if expected_link not in text:
            issues.append(diagnostic("ERROR", "P302", index_path, f"索引未登记：{relative}"))
        metadata, body, _ = parse_front_matter(path.read_text(encoding="utf-8"), path)
        if metadata.get("workflow_version") == "3":
            if metadata.get("type") == "hotfix":
                row = next(
                    (
                        line
                        for line in text.splitlines()
                        if f"[{path.name}]" in line
                    ),
                    "",
                )
            else:
                row = next(
                    (line for line in text.splitlines() if line.startswith(f"| {metadata['plan_id']} |")),
                    "",
                )
            progress = PROGRESS_RE.search(body)
            expected_progress = f"{progress.group(1)}/{progress.group(2)}" if progress else ""
            expected_status = PLAN_STATUS_LABELS.get(metadata.get("status", ""), "")
            if not row or expected_status not in row or expected_progress not in row:
                issues.append(
                    diagnostic(
                        "ERROR",
                        "P304",
                        index_path,
                        f"{metadata['plan_id']} 的状态或进度与 Plan 不一致",
                    )
                )
    plans = load_v3_plans(plan_files)
    execution_ids = {
        plan_id
        for plan_id, (_, metadata, _) in plans.items()
        if metadata.get("status") not in {"completed", "abandoned"}
    }
    acceptance_ids = {
        plan_id
        for plan_id, (_, metadata, _) in plans.items()
        if metadata.get("status") == "verifying"
    }
    queue = parse_queue(text, "当前执行队列")
    queue_set = set(queue)
    if len(queue) != len(queue_set) or queue_set != execution_ids:
        missing = sorted(execution_ids - queue_set)
        extra = sorted(queue_set - execution_ids)
        details = []
        if missing:
            details.append(f"缺少：{', '.join(missing)}")
        if extra:
            details.append(f"多余：{', '.join(extra)}")
        if len(queue) != len(queue_set):
            details.append("存在重复项")
        issues.append(
            diagnostic(
                "ERROR",
                "P306",
                index_path,
                f"当前执行队列与活跃 Plan 不一致（{'；'.join(details)}）",
            )
        )
    else:
        positions = {plan_id: index for index, plan_id in enumerate(queue)}
        for plan_id, (_, _, body) in plans.items():
            if plan_id not in execution_ids:
                continue
            for dependency in parse_plan_dependencies(body):
                if dependency not in plans:
                    continue
                dependency_status = plans[dependency][1].get("status")
                if dependency_status == "completed":
                    continue
                if dependency not in execution_ids:
                    issues.append(
                        diagnostic(
                            "ERROR",
                            "P306",
                            index_path,
                            f"执行队列未获放行：{plan_id} 的前置 {dependency} 状态为 "
                            f"{dependency_status}，且不在执行队列中",
                        )
                    )
                elif positions[dependency] > positions[plan_id]:
                    issues.append(
                        diagnostic(
                            "ERROR",
                            "P306",
                            index_path,
                            f"执行队列逆序：{dependency} 必须位于 {plan_id} 之前",
                        )
                    )
    acceptance_queue = parse_queue(text, "当前验收队列")
    acceptance_queue_set = set(acceptance_queue)
    if (
        len(acceptance_queue) != len(acceptance_queue_set)
        or acceptance_queue_set != acceptance_ids
    ):
        missing = sorted(acceptance_ids - acceptance_queue_set)
        extra = sorted(acceptance_queue_set - acceptance_ids)
        details = []
        if missing:
            details.append(f"缺少：{', '.join(missing)}")
        if extra:
            details.append(f"多余：{', '.join(extra)}")
        if len(acceptance_queue) != len(acceptance_queue_set):
            details.append("存在重复项")
        issues.append(
            diagnostic(
                "ERROR",
                "P307",
                index_path,
                f"当前验收队列与待验收 Plan 不一致（{'；'.join(details)}）",
            )
        )
    for target in re.findall(r"\]\((\.\./[^)]+\.md)\)", text):
        resolved = (index_path.parent / target).resolve()
        if not resolved.exists():
            issues.append(diagnostic("ERROR", "P303", index_path, f"失效链接：{target}"))
    return issues


def validate_templates(repo_root: Path) -> list[Diagnostic]:
    templates = repo_root / "plan/templates"
    required = {
        "main-plan-template.md": ["workflow_version: 3", "## 进度追踪", "## 验收记录"],
        "hotfix-template.md": ["workflow_version: 3", "parent_plan:", "## 进度追踪"],
        "archive-decision-template.md": ["## Before（变更前）", "## After（变更后）", "## 回滚可能性"],
    }
    issues: list[Diagnostic] = []
    for name, markers in required.items():
        path = templates / name
        if not path.exists():
            issues.append(diagnostic("ERROR", "P401", path, "缺少 Plan 模板"))
            continue
        text = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                issues.append(diagnostic("ERROR", "P402", path, f"模板缺少结构：{marker}"))
    return issues


def validate_repository(
    repo_root: Path, paths: list[Path] | None, strict: bool, verify_git: bool
) -> list[Diagnostic]:
    plan_files = paths or discover_plan_files(repo_root)
    issues: list[Diagnostic] = []
    for path in plan_files:
        issues.extend(validate_plan_file(path, strict=strict, verify_git=verify_git))
    if paths is None:
        issues.extend(validate_index(repo_root, plan_files))
        issues.extend(validate_dependencies(repo_root, plan_files))
        issues.extend(validate_templates(repo_root))
    elif paths == []:
        issues.extend(validate_index(repo_root, plan_files))
        issues.extend(validate_dependencies(repo_root, plan_files))
        issues.extend(validate_templates(repo_root))
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="指定 Plan 文件；默认扫描 plan/")
    parser.add_argument("--strict", action="store_true", help="严格校验 workflow v3")
    parser.add_argument("--verify-git", action="store_true", help="验证任务 commit hash")
    args = parser.parse_args()

    repo_root = find_repo_root(Path.cwd())
    paths = [path.resolve() for path in args.paths] if args.paths else None
    issues = validate_repository(repo_root, paths, args.strict, args.verify_git)
    for item in issues:
        try:
            display_path = item.path.resolve().relative_to(repo_root.resolve())
        except ValueError:
            display_path = item.path
        print(f"{item.level} {item.rule} {display_path}: {item.message}")
    errors = sum(item.level == "ERROR" for item in issues)
    warnings = sum(item.level == "WARNING" for item in issues)
    print(f"Plan validation: {errors} error(s), {warnings} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
