#!/usr/bin/env python3
"""Validate CRAG-Demo constraint documents for drift.

Checks:
  1. AGENTS.md and CLAUDE.md are byte-identical.
  2. Relative Markdown links in entry files and constraints/ are resolvable.
  3. Every Compose service is registered in the Docker current implementation index.
  4. Deprecated terms are absent (with per-file allowed-context rules).

Exit 0 when no errors; non-zero when errors are found.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import NamedTuple


class Diagnostic(NamedTuple):
    level: str
    code: str
    message: str


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def find_repo_root(path: Path) -> Path:
    current = path.resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return Path.cwd()


def read_text(root: Path, rel: str) -> str:
    return (root / rel).read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# Check 1 – Entry-file identity
# ---------------------------------------------------------------------------

def check_entry_identity(root: Path) -> list[Diagnostic]:
    a = (root / "AGENTS.md").read_bytes()
    c = (root / "CLAUDE.md").read_bytes()
    if a != c:
        return [Diagnostic("ERROR", "ENTRY_MISMATCH",
                           "AGENTS.md 与 CLAUDE.md 内容不一致。请确保两个文件字节完全一致。")]
    return []


# ---------------------------------------------------------------------------
# Check 2 – Relative Markdown links
# ---------------------------------------------------------------------------

# Match [text](path.md) or [text](./path.md) – only .md files
LINK_RE = re.compile(r"\[([^\]]+)\]\((\.[^)]*\.md)\)")

def collect_md_files(root: Path) -> set[str]:
    """Return relative paths of all .md files in constraints/ plus entry files."""
    result: set[str] = set()
    for f in (root / "constraints").rglob("*.md"):
        result.add(str(f.relative_to(root)))
    result.add("AGENTS.md")
    result.add("CLAUDE.md")
    return result


def check_links(root: Path) -> list[Diagnostic]:
    """Check that relative .md links in entry files and constraints/*.md resolve."""
    all_md = collect_md_files(root)
    diagnostics: list[Diagnostic] = []

    scan_files: list[str] = []
    for candidate in ["AGENTS.md", "CLAUDE.md"]:
        if (root / candidate).exists():
            scan_files.append(candidate)
    for f in sorted((root / "constraints").rglob("*.md")):
        scan_files.append(str(f.relative_to(root)))

    for rel_path in sorted(scan_files):
        text = read_text(root, rel_path)
        src_dir = Path(rel_path).parent
        for match in LINK_RE.finditer(text):
            target = match.group(2)
            candidate = root / src_dir / target
            resolved = str(candidate.resolve().relative_to(root.resolve()))
            if resolved not in all_md:
                diagnostics.append(
                    Diagnostic("ERROR", "LINK_BROKEN",
                               f"{rel_path} 中的链接 \"{target}\" 无法解析到 {resolved}"))
    return diagnostics


# ---------------------------------------------------------------------------
# Check 3 – Compose service registration
# ---------------------------------------------------------------------------

def parse_compose_services(root: Path) -> set[str] | None:
    """Parse top-level service names from docker-compose.yml.

    This is a deliberately limited parser.  It looks for a ``services:`` line
    at column 0 and then collects top-level keys (indented by 2 spaces, not
    comments).  Returns None when the file does not exist; returns a set
    (possibly empty) when the file exists.

    Callers must treat an empty set from an existing file as a parse failure
    to avoid silent drift.
    """
    compose_path = root / "docker-compose.yml"
    if not compose_path.exists():
        return None

    lines = compose_path.read_text(encoding="utf-8").splitlines()
    services: list[str] = []
    in_services = False
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith("services:") and not line.startswith(" "):
            in_services = True
            continue
        if in_services:
            # Stop at a top-level key (column 0, non-empty, non-comment)
            if line and not line.startswith((" ", "#")):
                break
            # Top-level service keys are indented by exactly 2 spaces
            m = re.match(r"^  ([a-zA-Z0-9_-]+):", line)
            if m:
                services.append(m.group(1))
    return set(services)


def parse_docker_index_services(root: Path) -> set[str]:
    """Extract backtick-quoted service names from ``### 5.N`` headings in
    constraints/docker-structure.md."""
    doc = read_text(root, "constraints/docker-structure.md")
    services: set[str] = set()
    # Match "### 5.N `svc-name` — ..."
    for m in re.finditer(r"^###\s+5\.\d+\s+`([^`]+)`", doc, re.MULTILINE):
        services.add(m.group(1))
    return services


def check_compose_services(root: Path) -> list[Diagnostic]:
    compose_services = parse_compose_services(root)
    if compose_services is None:
        return []  # No Compose file present — nothing to check

    if not compose_services:
        # File exists but parser found zero services — unambiguous failure.
        return [Diagnostic("ERROR", "COMPOSE_PARSE_FAILED",
                           "docker-compose.yml 存在但未能解析任何服务。"
                           "请检查文件格式或升级解析方式。")]

    index_services = parse_docker_index_services(root)
    diagnostics: list[Diagnostic] = []
    for svc in sorted(compose_services):
        if svc not in index_services:
            diagnostics.append(
                Diagnostic("ERROR", "COMPOSE_SERVICE_UNREGISTERED",
                           f"Compose 服务 \"{svc}\" 未在 constraints/docker-structure.md "
                           f"当前服务索引（5 节）中登记。"))
    return diagnostics


# ---------------------------------------------------------------------------
# Check 3b – Topology test script existence
# ---------------------------------------------------------------------------

def check_topology_script(root: Path) -> list[Diagnostic]:
    """Verify platform_topology_test.sh exists."""
    script = root / "scripts" / "tests" / "http" / "platform_topology_test.sh"
    if not script.exists():
        return [Diagnostic("ERROR", "TOPOLOGY_SCRIPT_MISSING",
                           "scripts/tests/http/platform_topology_test.sh 不存在。"
                           "plan_14 要求该脚本用于平台拓扑验收。")]
    return []


# ---------------------------------------------------------------------------
# Check 3c – Internal port exposure
# ---------------------------------------------------------------------------

INTERNAL_ONLY_SERVICES = {
    "access-service",
    "knowledge-service",
}

def check_internal_port_exposure(root: Path) -> list[Diagnostic]:
    """Verify internal-only services don't expose ports to host."""
    compose_path = root / "docker-compose.yml"
    if not compose_path.exists():
        return []

    text = compose_path.read_text(encoding="utf-8")
    lines = text.splitlines()
    diagnostics: list[Diagnostic] = []

    current_service: str | None = None
    in_ports = False

    for line in lines:
        # Detect service start (indented by exactly 2 spaces)
        svc_match = re.match(r"^  ([a-zA-Z0-9_-]+):\s*$", line)
        if svc_match:
            current_service = svc_match.group(1)
            in_ports = False
            continue

        if current_service is None:
            continue

        # Detect ports section under current service
        if re.match(r"^    ports:\s*$", line):
            in_ports = True
            continue

        # Detect end of ports section (new key at same or lower indent)
        if in_ports and re.match(r"^    [a-zA-Z]", line):
            in_ports = False

        # Check for host port mapping under internal-only service
        if in_ports and current_service in INTERNAL_ONLY_SERVICES:
            if re.match(r'^\s+-\s+"?\d+:\d+"?', line):
                diagnostics.append(
                    Diagnostic("ERROR", "INTERNAL_PORT_EXPOSED",
                               f"Compose 服务 \"{current_service}\" 不应暴露端口到宿主机。"
                               "内部服务端口只能在 Compose 私有网络内访问。"))
                in_ports = False  # Only report once per service

    return diagnostics


# ---------------------------------------------------------------------------
# Check 4 – Deprecated / prohibited terms
# ---------------------------------------------------------------------------

# Each entry:
#   (pattern, replacement, error_code, context_allow, per_file_rules)
#
# - pattern: the forbidden literal term.
# - replacement: what should be used instead (shown in error message).
# - error_code: diagnostic code.
# - context_allow: optional regex.  If non-empty, a hit is only flagged when
#     the line containing the term does NOT match context_allow.
# - per_file_rules: optional dict mapping relative file path →
#     "allow" (skip the file entirely for this term).
DEPRECATED_TERMS: list[tuple[str, str, str, str, dict[str, str]]] = [
    (
        "迁移期例外",
        "受控架构例外",
        "TERM_DEPRECATED",
        "",
        {},
    ),
    (
        "非单元测试",
        "四层测试分类术语（单元/组件/架构/Docker HTTP 回归）",
        "TERM_DEPRECATED",
        "",
        {},
    ),
    (
        "crag-admin",
        "crag-api（当前模块名）",
        "TERM_DEPRECATED",
        # Allowed only in compatibility notices about the rename itself.
        r"禁止新增.*crag-admin|crag-admin\s*→\s*crag-api|"
        r"不再使用.*crag-admin|已重命名.*crag-admin|"
        r"crag-admin.*已重命名|"
        r"`crag-admin`\s*[→]\s*`crag-api`|"
        r"旧模块.*crag-admin|crag-admin.*旧模块",
        {},
    ),
    (
        "app-smoke",
        "rag-service-smoke（当前 Compose 服务名）",
        "TERM_DEPRECATED",
        # Allowed only in historical/Plan references or migration notices.
        r"历史|归档|plan_|Plan |hotfix|迁移|替代|旧|原",
        # Skip plan files and docker_readiness_test.sh (which may reference migration).
        {},
    ),
    (
        "crag-app",
        "五个独立 Application 组合根（crag-rag-service 等）",
        "TERM_DEPRECATED",
        # Allowed only in historical/Plan references or migration notices.
        r"历史|归档|plan_|Plan |hotfix|迁移|替代|旧|原|已删除|不再",
        {},
    ),
]


def check_terms(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    scan_files: list[str] = []
    for candidate in ["AGENTS.md", "CLAUDE.md"]:
        if (root / candidate).exists():
            scan_files.append(candidate)
    for f in sorted((root / "constraints").rglob("*.md")):
        scan_files.append(str(f.relative_to(root)))

    for rel_path in sorted(scan_files):
        text = read_text(root, rel_path)
        lines = text.splitlines()
        for pattern, replacement, code, context_allow, per_file in DEPRECATED_TERMS:
            # Check if this file has a per-file rule
            rule = per_file.get(rel_path, "")
            if rule == "allow":
                continue
            for m in re.finditer(re.escape(pattern), text):
                line_no = text[:m.start()].count("\n")
                line_text = lines[line_no] if 0 <= line_no < len(lines) else ""
                line_no += 1  # Convert to 1-based
                # If context_allow is set and the line matches it, skip this hit
                if context_allow and re.search(context_allow, line_text):
                    continue
                diagnostics.append(
                    Diagnostic("ERROR", code,
                               f"{rel_path}:{line_no} 包含废弃术语 \"{pattern}\"。"
                               f"请使用 \"{replacement}\"。"))

    return diagnostics


# ---------------------------------------------------------------------------
# Check 5 – Topology script critical assertions use fail, not warn
# ---------------------------------------------------------------------------

def check_topology_critical_assertions(root: Path) -> list[Diagnostic]:
    """Verify platform_topology_test.sh uses fail for downstreamConnectivity checks."""
    script = root / "scripts" / "tests" / "http" / "platform_topology_test.sh"
    if not script.exists():
        return []

    text = script.read_text(encoding="utf-8")
    diagnostics: list[Diagnostic] = []

    if "downstreamConnectivity" in text and re.search(
            r"warn\s+.*downstreamConnectivity", text):
        diagnostics.append(
            Diagnostic("ERROR", "TOPOLOGY_WARN_ON_CRITICAL",
                       "platform_topology_test.sh 对 downstreamConnectivity 使用 warn 而非 fail。"
                       "关键 Probe 断言必须使用硬断言（fail）。"))

    return diagnostics


# ---------------------------------------------------------------------------
# Check 5b – Three-account coverage in topology script
# ---------------------------------------------------------------------------

def check_topology_three_account_coverage(root: Path) -> list[Diagnostic]:
    """Verify topology script covers all three accounts for same-schema and cross-schema."""
    script = root / "scripts" / "tests" / "http" / "platform_topology_test.sh"
    if not script.exists():
        return []

    text = script.read_text(encoding="utf-8")
    diagnostics: list[Diagnostic] = []

    schemas = ["access", "knowledge", "rag"]
    for schema in schemas:
        if f"CREATE TABLE" not in text or schema not in text:
            diagnostics.append(
                Diagnostic("ERROR", "TOPOLOGY_MISSING_ACCOUNT_SAME_SCHEMA",
                           f"platform_topology_test.sh 缺少 {schema} Schema 的同 Schema 成功验证。"
                           "三个账号都必须验证自身 Schema 可写。"))
            break

    if "does not exist" in text:
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if "does not exist" in line and "permission denied" in line:
                diagnostics.append(
                    Diagnostic("ERROR", "TOPOLOGY_FALSE_POSITIVE_PERMISSION",
                               f"platform_topology_test.sh:{i+1} 使用 'does not exist' "
                               "作为权限拒绝证据。跨 Schema 权限测试必须使用真实存在的对象，"
                               "且错误必须为 'permission denied'。"))

    return diagnostics


# ---------------------------------------------------------------------------
# Check 6 – Docker persistence path consistency
# ---------------------------------------------------------------------------

def check_docker_persistence_path(root: Path) -> list[Diagnostic]:
    """Verify docker-structure.md uses data/pgdata-platform/ as current path."""
    doc_path = root / "constraints" / "docker-structure.md"
    if not doc_path.exists():
        return []

    text = doc_path.read_text(encoding="utf-8")
    diagnostics: list[Diagnostic] = []
    lines = text.splitlines()

    for i, line in enumerate(lines):
        # Check for data/pgdata/ used as a current hard constraint (not rollback context)
        if "data/pgdata/" in line and "data/pgdata-platform/" not in line:
            line_no = i + 1
            if "回滚" in line or "旧" in line or "保留" in line:
                continue
            diagnostics.append(
                Diagnostic("ERROR", "DOCKER_PERSISTENCE_DRIFT",
                           f"constraints/docker-structure.md:{line_no} 仍引用旧持久化路径 "
                           "\"data/pgdata/\"。当前平台使用 \"data/pgdata-platform/\"，"
                           "旧路径仅保留回滚。"))

    return diagnostics


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def validate(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    diagnostics.extend(check_entry_identity(root))
    diagnostics.extend(check_links(root))
    diagnostics.extend(check_compose_services(root))
    diagnostics.extend(check_topology_script(root))
    diagnostics.extend(check_internal_port_exposure(root))
    diagnostics.extend(check_terms(root))
    diagnostics.extend(check_topology_critical_assertions(root))
    diagnostics.extend(check_topology_three_account_coverage(root))
    diagnostics.extend(check_docker_persistence_path(root))
    return diagnostics


def main() -> int:
    repo_root = find_repo_root(Path.cwd())
    diagnostics = validate(repo_root)
    for item in diagnostics:
        print(f"{item.level} [{item.code}]: {item.message}")
    errors = sum(1 for item in diagnostics if item.level == "ERROR")
    warnings = sum(1 for item in diagnostics if item.level == "WARNING")
    parts = [f"{errors} error(s)"]
    if warnings:
        parts.append(f"{warnings} warning(s)")
    print(f"Constraint validation: {', '.join(parts)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
