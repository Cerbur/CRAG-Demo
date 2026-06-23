#!/usr/bin/env python3
"""Validate CRAG-Demo Gradle project dependency declarations against whitelist."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import NamedTuple


class Diagnostic(NamedTuple):
    level: str
    message: str


# Whitelist from constraints/package-structure.md section 4.
# Keys are target module names (crag-api is the target name for current crag-admin).
# crag-app has special "all" handling.
MODULE_WHITELIST: dict[str, set[str]] = {
    "crag-id": set(),
    "crag-common": set(),
    "crag-storage": {"crag-common"},
    "crag-retrieval": {"crag-storage", "crag-common"},
    "crag-ingestion": {"crag-retrieval", "crag-storage", "crag-common", "crag-id"},
    "crag-query": {"crag-retrieval", "crag-common"},
    "crag-api": {"crag-ingestion", "crag-query", "crag-common"},
    "crag-smoke": {
        "crag-api",
        "crag-ingestion",
        "crag-query",
        "crag-retrieval",
        "crag-storage",
        "crag-common",
        "crag-id",
    },
    "crag-platform-contracts": set(),
    "crag-grpc-runtime": set(),
    # crag-app: allowed to depend on all application modules for runtime assembly.
    # Handled specially below.
}

# Name mapping from current module names to target whitelist keys.
# Empty after plan_9 9.2: crag-admin was renamed to crag-api and removed.
MODULE_NAME_MAP: dict[str, str] = {}

# Special modules.
APP_MODULES = {
    "crag-access-service",
    "crag-knowledge-service",
    "crag-rag-service",
    "crag-console-api",
    "crag-open-api",
}
SPECIAL_MODULES = APP_MODULES

PROJECT_DEP_RE = re.compile(
    r'(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly)\s*\(\s*project\s*\(\s*"([^"]+)"\s*\)\s*\)'
)

MODULE_NAME_RE = re.compile(r'"([^"]+)"')


def find_repo_root(path: Path) -> Path:
    current = path.resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return Path.cwd()


def parse_settings(repo_root: Path) -> list[str]:
    """Parse settings.gradle.kts to find all included modules."""
    settings_path = repo_root / "settings.gradle.kts"
    if not settings_path.exists():
        return []
    text = settings_path.read_text(encoding="utf-8")
    modules: list[str] = []
    in_include = False
    for line in text.splitlines():
        stripped = line.strip()
        if "include(" in stripped:
            in_include = True
        if in_include:
            for match in MODULE_NAME_RE.finditer(stripped):
                modules.append(match.group(1))
        if ")" in stripped and in_include:
            in_include = False
    return modules


def parse_dependencies(build_path: Path) -> set[str]:
    """Parse a module's build.gradle.kts to find its project dependencies."""
    if not build_path.exists():
        return set()
    text = build_path.read_text(encoding="utf-8")
    deps: set[str] = set()
    for match in PROJECT_DEP_RE.finditer(text):
        deps.add(match.group(1))
    return deps


def resolve_whitelist(module: str) -> set[str] | None:
    """Resolve the whitelist for a module. Returns None for special modules."""
    if module in SPECIAL_MODULES:
        return None  # Special handling
    mapped = MODULE_NAME_MAP.get(module, module)
    return MODULE_WHITELIST.get(mapped)


def detect_cycles(
    modules: list[str], dependency_graph: dict[str, set[str]]
) -> list[str]:
    """Detect dependency cycles. Returns list of cycle descriptions."""
    cycles: list[str] = []
    visited: set[str] = set()
    rec_stack: list[str] = []

    def visit(node: str) -> None:
        if node in rec_stack:
            start = rec_stack.index(node)
            cycle = rec_stack[start:] + [node]
            cycles.append(" → ".join(cycle))
            return
        if node in visited:
            return
        visited.add(node)
        rec_stack.append(node)
        for neighbor in sorted(dependency_graph.get(node, set())):
            visit(neighbor)
        rec_stack.pop()

    for module in sorted(modules):
        visit(module)
    return cycles


def validate(repo_root: Path) -> list[Diagnostic]:
    """Validate all module project dependencies against the whitelist."""
    diagnostics: list[Diagnostic] = []

    modules = parse_settings(repo_root)
    if not modules:
        diagnostics.append(
            Diagnostic("ERROR", "未在 settings.gradle.kts 中发现任何模块")
        )
        return diagnostics

    # Build dependency graph with normalized (no colon) dependency names.
    dependency_graph: dict[str, set[str]] = {}
    for module in modules:
        build_path = repo_root / module / "build.gradle.kts"
        deps = parse_dependencies(build_path)
        dependency_graph[module] = {d.lstrip(":") for d in deps}

    # Check cycles
    cycles = detect_cycles(modules, dependency_graph)
    for cycle in cycles:
        diagnostics.append(Diagnostic("ERROR", f"检测到模块依赖环：{cycle}"))

    # Check each module's dependencies against whitelist
    all_other_modules = set(modules)

    for module in sorted(modules):
        deps = dependency_graph.get(module, set())
        whitelist = resolve_whitelist(module)

        if whitelist is None:
            # Special module (application module): allowed to depend on all other modules
            if module in APP_MODULES:
                continue
            # Unknown special module
            continue

        # Check each dependency
        for dep in sorted(deps):
            # Dep names already normalized during graph construction.
            if dep not in all_other_modules:
                # Not a project-internal dependency
                continue
            if dep not in whitelist:
                diagnostics.append(
                    Diagnostic(
                        "ERROR",
                        f"模块 {module} 对 {dep} 的 project 依赖不在白名单中。"
                        f"白名单允许：{', '.join(sorted(whitelist)) if whitelist else '无'}",
                    )
                )

    # Check for missing required dependencies? No - the whitelist defines allowed deps,
    # not required ones. Modules may not need all allowed deps.

    return diagnostics


def main() -> int:
    repo_root = find_repo_root(Path.cwd())
    diagnostics = validate(repo_root)
    for item in diagnostics:
        print(f"{item.level}: {item.message}")
    errors = sum(1 for item in diagnostics if item.level == "ERROR")
    print(f"Module dependency validation: {errors} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
