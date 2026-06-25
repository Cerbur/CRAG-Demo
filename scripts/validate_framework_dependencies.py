#!/usr/bin/env python3
"""
Validate framework version governance for CRAG-Demo.

Checks:
  1. gradle/libs.versions.toml is the sole source of truth for Boot, Spring AI,
     and dependency-management plugin versions.
  2. No submodule build.gradle.kts hardcodes Boot, Spring AI or
     dependency-management versions.
  3. No milestone/snapshot repository remains.
  4. No Gradle platform() manages the same framework that the
     dependency-management plugin already covers.
  5. Spring AI BOM is only imported in crag-ingestion (plan_7 will add crag-query).
  6. spring-ai-commons is the only Spring AI dependency; no
     spring-ai-openai-spring-boot-starter, spring-ai-transformers, or
     other provider/transformer artifacts.
  7. Old OpenAI autoconfig exclusion and dummy API key are absent from
     application.yml files.

Usage: python3 scripts/validate_framework_dependencies.py
Exit code 0 on success, 1 on violations.
"""

import os
import re
import sys
import tomllib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Path segments marking generated, VCS, build-tool, or agent-workspace directories.
# Files beneath them (e.g. stale agent worktrees holding old module layouts) must
# never be treated as authoritative repo sources.
IGNORED_PATH_PARTS = ("/build/", "/bin/", "/.claude/", "/.git/", "/.gradle/", "/.idea/")


def is_ignored_path(path: Path) -> bool:
    text = str(path)
    return any(part in text for part in IGNORED_PATH_PARTS)


def read_toml(path: Path) -> dict:
    with open(path, "rb") as f:
        return tomllib.load(f)


def read_file(path: Path) -> str:
    return path.read_text()


def check_catalog_versions() -> list[str]:
    """Verify catalog declares the required versions and no submodule hardcodes them."""
    errors = []
    catalog_path = REPO_ROOT / "gradle" / "libs.versions.toml"
    if not catalog_path.exists():
        errors.append("MISSING: gradle/libs.versions.toml must exist as version catalog")
        return errors

    catalog = read_toml(catalog_path)

    versions = catalog.get("versions", {})
    required = {
        "spring-boot": "4.1.0",
        "spring-ai": "2.0.0",
        "spring-dependency-management": "1.1.7",
        "grpc": "1.82.0",
        "protobuf-version": "4.35.1",
        "protobuf-plugin": "0.10.0",
    }
    for key, expected in required.items():
        actual = versions.get(key)
        if actual is None:
            errors.append(f"Catalog versions missing [{key}]")
        elif actual != expected:
            errors.append(f"Catalog [{key}] version mismatch: expected={expected}, got={actual}")

    # Check plugins section
    plugins = catalog.get("plugins", {})
    for plugin_id in ("spring-boot", "spring-dependency-management"):
        if plugin_id not in plugins:
            errors.append(f"Catalog plugins missing [{plugin_id}]")

    # Check that spring-ai-commons is declared in the libraries section
    libraries = catalog.get("libraries", {})
    if "spring-ai-commons" not in libraries:
        errors.append("Catalog libraries missing [spring-ai-commons]")
    else:
        lib = libraries["spring-ai-commons"]
        if lib.get("version", {}).get("ref") != "spring-ai":
            errors.append("Catalog library spring-ai-commons must use version.ref = spring-ai")

    # Scan all build.gradle.kts for hardcoded versions of these frameworks
    # Root build.gradle.kts imports Boot BOM via catalog; crag-ingestion imports Spring AI BOM
    forbidden_hardcoded = [
        (r'"org\.springframework\.boot".*version\s*"', "hardcoded Boot plugin version"),
        (r"spring-boot-dependencies:\d", "hardcoded Boot BOM version"),
        (r"spring-ai-bom:\d", "hardcoded Spring AI BOM version"),
        (r'spring-ai-commons:\d', "hardcoded spring-ai-commons version (use catalog library)"),
        (r"spring-ai-openai-spring-boot-starter", "OpenAI starter (must be removed)"),
        (r"spring-ai-transformers", "spring-ai-transformers (forbidden)"),
    ]

    for kts_path in REPO_ROOT.rglob("build.gradle.kts"):
        rel = str(kts_path.relative_to(REPO_ROOT))
        # Exclude build output directories
        if is_ignored_path(kts_path):
            continue

        content = read_file(kts_path)
        for pattern, desc in forbidden_hardcoded:
            if re.search(pattern, content):
                # Root build.gradle.kts imports Boot BOM via catalog — not hardcoded
                if desc == "hardcoded Boot BOM version" and rel == "build.gradle.kts":
                    continue
                # crag-ingestion imports Spring AI BOM via catalog reference — not hardcoded
                if desc == "hardcoded Spring AI BOM version" and "crag-ingestion" in rel:
                    continue
                errors.append(f"{rel}: {desc}")

    return errors


def check_repositories() -> list[str]:
    """Verify no milestone/snapshot repository remains."""
    errors = []
    repo_patterns = [
        (r'repo\.spring\.io/milestone', "Spring milestone repository"),
        (r'repo\.spring\.io/snapshot', "Spring snapshot repository"),
        (r'oss\.sonatype\.org', "Sonatype snapshot repository"),
    ]

    for kts_path in REPO_ROOT.rglob("*.gradle.kts"):
        if is_ignored_path(kts_path):
            continue
        content = read_file(kts_path)
        for pattern, desc in repo_patterns:
            if re.search(pattern, content):
                errors.append(f"{kts_path.relative_to(REPO_ROOT)}: forbidden repository — {desc}")

    return errors


def check_no_platform_mixing() -> list[str]:
    """Verify no Gradle platform() manages Boot or Spring AI BOM (dependency-management plugin handles this)."""
    errors = []
    platform_patterns = [
        r'platform\("org\.springframework\.boot:spring-boot-dependencies',
        r'platform\("org\.springframework\.ai:spring-ai-bom',
    ]

    for kts_path in REPO_ROOT.rglob("build.gradle.kts"):
        if is_ignored_path(kts_path):
            continue
        content = read_file(kts_path)
        for pattern in platform_patterns:
            if re.search(pattern, content):
                errors.append(
                    f"{kts_path.relative_to(REPO_ROOT)}: platform() for framework BOM "
                    f"is forbidden — dependency-management plugin handles BOM import"
                )

    return errors


def check_spring_ai_boundary() -> list[str]:
    """Verify Spring AI BOM only in crag-ingestion and only spring-ai-commons is used."""
    errors = []

    # Spring AI BOM is centralized in crag-rag-service after plan_16 consolidation.
    for kts_path in REPO_ROOT.rglob("build.gradle.kts"):
        if is_ignored_path(kts_path):
            continue
        content = read_file(kts_path)
        rel = str(kts_path.relative_to(REPO_ROOT))
        if re.search(r"spring-ai-bom", content):
            allowed = ("crag-rag-service",)
            if not any(a in rel for a in allowed):
                errors.append(
                    f"{rel}: Spring AI BOM only allowed in "
                    f"{'/'.join(allowed)}/build.gradle.kts"
                )

    # Only spring-ai-commons and spring-ai-anthropic are allowed; both are
    # consolidated into crag-rag-service after plan_16.
    forbidden_ai_modules = [
        "spring-ai-openai",
        "spring-ai-transformers",
        "spring-ai-ollama",
        "spring-ai-vertex",
        "spring-ai-bedrock",
        "spring-ai-azure",
        "spring-ai-autoconfigure",
        "spring-ai-retry",
        "spring-ai-spring-boot-starter",
    ]

    for kts_path in REPO_ROOT.rglob("build.gradle.kts"):
        if is_ignored_path(kts_path):
            continue
        content = read_file(kts_path)
        for mod in forbidden_ai_modules:
            if mod in content:
                errors.append(f"{kts_path.relative_to(REPO_ROOT)}: forbidden dependency [{mod}]")

    return errors


def check_autoconfig_and_dummy_keys() -> list[str]:
    """Verify OpenAI autoconfig exclusion and dummy API keys are removed."""
    errors = []

    forbidden_config = [
        (r"OpenAiAutoConfiguration", "OpenAI autoconfig exclusion"),
        (r"dummy-plan\d-key", "dummy API key"),
    ]

    for yml_path in REPO_ROOT.rglob("application*.yml"):
        if is_ignored_path(yml_path):
            continue
        content = read_file(yml_path)
        for pattern, desc in forbidden_config:
            if re.search(pattern, content):
                errors.append(f"{yml_path.relative_to(REPO_ROOT)}: {desc} must be removed")

    return errors


def check_contracts_runtime_boundary() -> list[str]:
    """Verify crag-platform-contracts has no Spring/Runtime deps,
    and crag-grpc-runtime has no Contracts or business deps."""
    errors = []

    contracts_path = REPO_ROOT / "crag-platform-contracts" / "build.gradle.kts"
    if contracts_path.exists():
        content = read_file(contracts_path)
        if re.search(r"alias\(libs\.plugins\.spring\.boot\)", content):
            errors.append(
                "crag-platform-contracts/build.gradle.kts: Spring Boot plugin forbidden"
            )
        if re.search(r'spring-boot-starter', content):
            errors.append(
                "crag-platform-contracts/build.gradle.kts: Spring Boot starter forbidden"
            )
        if re.search(r'project\(":crag-grpc-runtime"\)', content):
            errors.append(
                "crag-platform-contracts/build.gradle.kts: must not depend on crag-grpc-runtime"
            )

    runtime_path = REPO_ROOT / "crag-grpc-runtime" / "build.gradle.kts"
    if runtime_path.exists():
        content = read_file(runtime_path)
        if re.search(r"alias\(libs\.plugins\.spring\.boot\)", content):
            errors.append(
                "crag-grpc-runtime/build.gradle.kts: Spring Boot plugin forbidden"
            )
        if re.search(r'project\(":crag-platform-contracts"\)', content):
            errors.append(
                "crag-grpc-runtime/build.gradle.kts: must not depend on crag-platform-contracts"
            )
        # After plan_16 the RAG business lives in the Application roots; the
        # gRPC runtime must not pull any of them in.
        business_modules = [
            "crag-rag-service",
            "crag-access-service",
            "crag-knowledge-service",
            "crag-console-api",
            "crag-open-api",
        ]
        for mod in business_modules:
            if re.search(rf'project\(":{mod}"\)', content):
                errors.append(
                    f"crag-grpc-runtime/build.gradle.kts: must not depend on {mod}"
                )

    return errors


def main() -> int:
    all_errors = []
    all_errors.extend(check_catalog_versions())
    all_errors.extend(check_repositories())
    all_errors.extend(check_no_platform_mixing())
    all_errors.extend(check_spring_ai_boundary())
    all_errors.extend(check_autoconfig_and_dummy_keys())
    all_errors.extend(check_contracts_runtime_boundary())

    if all_errors:
        print("Framework dependency validation FAILED:")
        for err in all_errors:
            print(f"  - {err}")
        return 1

    print("Framework dependency validation PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
