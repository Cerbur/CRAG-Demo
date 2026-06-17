#!/usr/bin/env python3
import re
from pathlib import Path


REQUIRED_REFERENCES = [
    "references/data-generation.md",
    "references/crag-test-endpoints.md",
    "references/scoring.md",
]

REQUIRED_SCRIPTS = [
    "scripts/generate_cases.py",
    "scripts/score_report.py",
    "scripts/validate_skill.py",
]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read(path):
    return Path(path).read_text(encoding="utf-8")


def parse_frontmatter(text):
    match = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    require(match is not None, "SKILL.md must start with YAML frontmatter")
    fields = {}
    for line in match.group(1).splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            fields[key.strip()] = value.strip()
    return fields


def validate(skill_dir):
    root = Path(skill_dir)
    require(root.exists(), f"skill directory does not exist: {root}")
    skill_md = root / "SKILL.md"
    openai_yaml = root / "agents" / "openai.yaml"
    require(skill_md.exists(), "missing SKILL.md")
    require(openai_yaml.exists(), "missing agents/openai.yaml")

    skill_text = read(skill_md)
    frontmatter = parse_frontmatter(skill_text)
    require(frontmatter.get("name") == "crag-benchmark", "frontmatter name must be crag-benchmark")
    require("benchmark" in frontmatter.get("description", "").lower(), "description must mention benchmark")
    require("Workflow" in skill_text, "SKILL.md must contain Workflow section")
    require("Resource Routing" in skill_text, "SKILL.md must contain Resource Routing section")

    openai_text = read(openai_yaml)
    require('display_name: "CRAG Benchmark"' in openai_text, "openai.yaml missing display_name")
    require("$crag-benchmark" in openai_text, "default_prompt must mention $crag-benchmark")

    for relative in REQUIRED_REFERENCES + REQUIRED_SCRIPTS:
        path = root / relative
        require(path.exists(), f"missing required resource: {relative}")
        require(path.stat().st_size > 0, f"resource is empty: {relative}")

    return {
        "ok": True,
        "references": len(REQUIRED_REFERENCES),
        "scripts": len(REQUIRED_SCRIPTS),
    }


def main():
    result = validate(Path(__file__).resolve().parents[1])
    print(result)


if __name__ == "__main__":
    main()
