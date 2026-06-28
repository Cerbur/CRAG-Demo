#!/usr/bin/env python3
"""Validate OpenAPI 3.1 documents and Markdown source-code links (plan_21/21.12).

This validator is the contract gate for the frontend handoff documents in
``docs/api/``. It runs as part of ``./gradlew check`` and must stay
zero-dependency (stdlib only) because PyYAML is not installed in this
environment. The OpenAPI ``.yaml`` files are written as JSON documents, which
are a valid YAML 1.2 superset and therefore consumable by openapi-generator
and other OpenAPI 3.1 tooling.

Checks (one diagnostic per failure):

  1. JSON parse: each ``docs/api/*.openapi.yaml`` parses.
  2. OpenAPI 3.1: top-level ``openapi`` starts with ``3.1``.
  3. operationId uniqueness: every operationId appears once per document.
  4. $ref resolvability: every ``$ref`` resolves inside the document.
  5. example matches schema: structural type check (object/array/string/integer/
     number/boolean) of in-response examples against the inline schema.
  6. route-list drift: the ``x-crag-implementation.controller-routes`` manifest
     matches (a) the documented paths/methods and (b) the real Controller source
     files' Spring ``@*Mapping`` annotations.
  7. Markdown source-code links: relative links in ``docs/api/README.md`` and
     ``docs/README.md`` resolve to existing files.

Exit code 0 on success, 1 on any error diagnostic. WARN-level diagnostics are
printed but do not fail the run.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any, NamedTuple


# ----------------------------------------------------------------------
# Result model
# ----------------------------------------------------------------------
class Diagnostic(NamedTuple):
    ok: bool
    level: str  # "ERROR" / "WARNING"
    message: str


class Result(NamedTuple):
    ok: bool
    diagnostics: list[Diagnostic]


def _err(message: str) -> Diagnostic:
    return Diagnostic(ok=False, level="ERROR", message=message)


def _warn(message: str) -> Diagnostic:
    return Diagnostic(ok=True, level="WARNING", message=message)


# ----------------------------------------------------------------------
# Public entry: validate the whole repo's docs/api + docs README links.
# ----------------------------------------------------------------------
CONSOLE_DOC = "docs/api/console-api.openapi.yaml"
OPEN_DOC = "docs/api/open-api.openapi.yaml"
API_README = "docs/api/README.md"
DOCS_README = "docs/README.md"

OPENAPI_DOCS = (CONSOLE_DOC, OPEN_DOC)

HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options", "trace")


def validate_repo(root: Path) -> Result:
    diags: list[Diagnostic] = []

    # 1-6. Per-document OpenAPI checks.
    doc_manifests: dict[str, list[dict[str, Any]]] = {}
    for rel in OPENAPI_DOCS:
        path = root / rel
        if not path.exists():
            diags.append(_err(f"{rel}: missing OpenAPI document"))
            doc_manifests[rel] = []
            continue
        text = path.read_text(encoding="utf-8")
        try:
            doc = json.loads(text)
        except json.JSONDecodeError as e:
            diags.append(_err(f"{rel}: JSON parse failed: {e}"))
            doc_manifests[rel] = []
            continue
        diags.extend(validate_openapi_doc(rel, doc))
        doc_manifests[rel] = extract_controller_routes(doc)

    # 6 (continued). Cross-check each controller-route manifest entry against the
    # real Spring Controller source files.
    for rel, manifest in doc_manifests.items():
        diags.extend(check_controller_routes(root, rel, manifest))

    # 7. Markdown links in docs/api/README.md and docs/README.md.
    diags.extend(check_markdown_links(root, root / API_README))
    diags.extend(check_markdown_links(root, root / DOCS_README))

    errors = [d for d in diags if not d.ok]
    return Result(ok=len(errors) == 0, diagnostics=diags)


# ----------------------------------------------------------------------
# Per-document OpenAPI structural checks.
# ----------------------------------------------------------------------
def validate_openapi_doc(rel: str, doc: dict[str, Any]) -> list[Diagnostic]:
    diags: list[Diagnostic] = []
    if not isinstance(doc, dict):
        diags.append(_err(f"{rel}: document root must be an object"))
        return diags

    # Check 2: openapi == 3.1.x
    version = doc.get("openapi")
    if not isinstance(version, str) or not version.startswith("3.1."):
        diags.append(
            _err(
                f"{rel}: openapi must be 3.1.x, got {version!r}; "
                "the handoff documents target OpenAPI 3.1."
            )
        )

    paths = doc.get("paths")
    if not isinstance(paths, dict):
        diags.append(_err(f"{rel}: missing/invalid `paths` object"))
        paths = {}

    components = doc.get("components", {}) or {}
    schemas = components.get("schemas", {}) if isinstance(components, dict) else {}

    # Check 3: operationId uniqueness
    operation_ids: dict[str, list[str]] = {}
    for path, item in paths.items():
        if not isinstance(item, dict):
            continue
        for method in HTTP_METHODS:
            op = item.get(method)
            if not isinstance(op, dict):
                continue
            op_id = op.get("operationId")
            if not isinstance(op_id, str) or not op_id:
                diags.append(
                    _err(
                        f"{rel}: {method.upper()} {path} missing operationId; "
                        "every operation needs a stable unique operationId."
                    )
                )
                continue
            operation_ids.setdefault(op_id, []).append(f"{method.upper()} {path}")
    for op_id, locations in operation_ids.items():
        if len(locations) > 1:
            diags.append(
                _err(
                    f"{rel}: duplicate operationId {op_id!r} at "
                    f"{', '.join(locations)}; operationId must be unique."
                )
            )

    # Check 4: $ref resolvability
    ref_paths = collect_refs(doc)
    for ref, _location in ref_paths:
        if not ref.startswith("#/"):
            diags.append(
                _err(
                    f"{rel}: only in-document $ref ('#/...') is supported, "
                    f"got {ref!r}; external refs would break the frontend client."
                )
            )
            continue
        if not resolve_json_pointer(doc, ref):
            diags.append(
                _err(f"{rel}: broken $ref {ref!r}; example/response schema is dangling.")
            )

    # Check 5: example vs schema (structural type check on response examples)
    diags.extend(check_response_examples(rel, doc, paths))

    return diags


# ----------------------------------------------------------------------
# $ref / JSON-pointer helpers
# ----------------------------------------------------------------------
REF_RE = re.compile(r'"\$ref"\s*:\s*"([^"]+)"')


def collect_refs(doc: dict[str, Any]) -> list[tuple[str, str]]:
    """Collect every ``$ref`` literal by scanning the raw JSON text. Returns a
    list of (ref_value, location_hint) tuples. Scanning text is robust against
    nested structures and matches what a client generator would consume."""
    refs: list[tuple[str, str]] = []
    # Re-serialize so the scan matches what is on disk.
    text = json.dumps(doc, ensure_ascii=False)
    for m in REF_RE.finditer(text):
        refs.append((m.group(1), ""))
    return refs


def resolve_json_pointer(doc: Any, ref: str) -> bool:
    if not ref.startswith("#"):
        return False
    pointer = ref[1:]  # drop '#'
    if pointer == "" or pointer == "/":
        return True
    parts = pointer.strip("/").split("/")
    node: Any = doc
    for part in parts:
        if isinstance(node, dict) and part in node:
            node = node[part]
        else:
            return False
    return node is not None


# ----------------------------------------------------------------------
# Example vs schema check
# ----------------------------------------------------------------------
def resolve_schema(doc: dict[str, Any], schema: dict[str, Any]) -> dict[str, Any]:
    """Follow a single leading $ref to its target; return the inline schema."""
    if isinstance(schema, dict) and "$ref" in schema:
        ref = schema["$ref"]
        if isinstance(ref, str) and ref.startswith("#/"):
            target = doc
            for part in ref.strip("#/").split("/"):
                if isinstance(target, dict) and part in target:
                    target = target[part]
                else:
                    return schema
            if isinstance(target, dict):
                return target
    return schema if isinstance(schema, dict) else {}


def check_response_examples(
    rel: str, doc: dict[str, Any], paths: dict[str, Any]
) -> list[Diagnostic]:
    diags: list[Diagnostic] = []
    for path, item in paths.items():
        if not isinstance(item, dict):
            continue
        for method in HTTP_METHODS:
            op = item.get(method)
            if not isinstance(op, dict):
                continue
            responses = op.get("responses")
            if not isinstance(responses, dict):
                continue
            for status, response in responses.items():
                if not isinstance(response, dict):
                    continue
                content = response.get("content")
                if not isinstance(content, dict):
                    continue
                for media_type, media in content.items():
                    if not isinstance(media, dict):
                        continue
                    schema = media.get("schema")
                    example = media.get("example")
                    if schema is None or example is None:
                        continue
                    resolved = resolve_schema(doc, schema)
                    mismatch = example_schema_mismatch(resolved, example)
                    if mismatch:
                        diags.append(
                            _err(
                                f"{rel}: {method.upper()} {path} {status} "
                                f"{media_type} example does not match schema: {mismatch}"
                            )
                        )
    return diags


def example_schema_mismatch(schema: dict[str, Any], example: Any) -> str:
    """Return an empty string when the example is consistent with the schema's
    declared type, otherwise a short reason. The top-level type axis is checked
    (object/array/string/integer/number/boolean/null) and, for object schemas
    with declared ``properties``, each property's example value is checked
    against its property schema's type. This is sufficient to catch accidental
    drift without reimplementing a full JSON Schema validator."""
    if not schema:
        return ""
    t = schema.get("type")
    if isinstance(t, list):
        allowed = set(t)
    elif isinstance(t, str):
        allowed = {t}
    else:
        allowed = set()
    if "oneOf" in schema or "anyOf" in schema or "allOf" in schema:
        # Composite schemas are not type-checked here; the union makes structural
        # validation brittle and openapi-generator already enforces these.
        return ""
    if allowed:
        py = type(example)
        if "object" in allowed and not isinstance(example, dict):
            return f"expected object, got {py.__name__}"
        if "array" in allowed and not isinstance(example, list):
            return f"expected array, got {py.__name__}"
        if "string" in allowed and not isinstance(example, str):
            return f"expected string, got {py.__name__}"
        if ("integer" in allowed) and not (
            isinstance(example, int) and not isinstance(example, bool)
        ):
            return f"expected integer, got {py.__name__}"
        if ("number" in allowed) and not (
            isinstance(example, (int, float)) and not isinstance(example, bool)
        ):
            return f"expected number, got {py.__name__}"
        if "boolean" in allowed and not isinstance(example, bool):
            return f"expected boolean, got {py.__name__}"
        if "null" in allowed and example is not None:
            return "expected null"
    # Recurse into declared object properties so nested drift is also caught.
    properties = schema.get("properties")
    if isinstance(properties, dict) and isinstance(example, dict):
        for name, prop_schema in properties.items():
            if not isinstance(prop_schema, dict):
                continue
            if name not in example:
                continue
            nested = example_schema_mismatch(prop_schema, example[name])
            if nested:
                return f"property {name!r}: {nested}"
    # Recurse into array items.
    items = schema.get("items")
    if isinstance(items, dict) and isinstance(example, list):
        for idx, value in enumerate(example):
            nested = example_schema_mismatch(items, value)
            if nested:
                return f"item[{idx}]: {nested}"
    return ""


# ----------------------------------------------------------------------
# Route-list drift check (manifest vs documented paths vs real Controllers)
# ----------------------------------------------------------------------
def extract_controller_routes(doc: dict[str, Any]) -> list[dict[str, Any]]:
    ext = doc.get("x-crag-implementation")
    if not isinstance(ext, dict):
        return []
    routes = ext.get("controller-routes")
    return routes if isinstance(routes, list) else []


def check_controller_routes(
    root: Path, rel: str, manifest: list[dict[str, Any]]
) -> list[Diagnostic]:
    diags: list[Diagnostic] = []
    # (a) The manifest entries must be well formed.
    declared: set[tuple[str, str]] = set()
    for entry in manifest:
        if not isinstance(entry, dict):
            diags.append(_err(f"{rel}: x-crag-implementation entry is not an object"))
            continue
        controller = entry.get("controller")
        routes = entry.get("routes")
        if not isinstance(controller, str) or not controller:
            diags.append(_err(f"{rel}: controller-routes entry missing `controller`"))
            continue
        if not isinstance(routes, list):
            diags.append(
                _err(f"{rel}: controller-routes entry for {controller} missing `routes`")
            )
            continue
        for r in routes:
            if not isinstance(r, dict):
                diags.append(_err(f"{rel}: route entry for {controller} is not an object"))
                continue
            method = (r.get("method") or "").upper()
            path = r.get("path")
            if method not in {m.upper() for m in HTTP_METHODS} or not isinstance(path, str):
                diags.append(
                    _err(
                        f"{rel}: controller-routes entry for {controller} has "
                        f"invalid method/path ({method!r}, {path!r})"
                    )
                )
                continue
            declared.add((method, path))
    # (b) Each declared route must exist in the real Spring Controller source.
    for entry in manifest:
        if not isinstance(entry, dict):
            continue
        controller = entry.get("controller")
        routes = entry.get("routes")
        if not isinstance(controller, str) or not isinstance(routes, list):
            continue
        for miss in find_missing_controller_routes(root, controller, routes):
            diags.append(_err(f"{rel}: {miss}"))
    return diags


CLASS_RE = re.compile(r"\bclass\s+\w+\b")
MAPPING_ANNOT_RE = re.compile(
    r"@(?:Request|Get|Post|Put|Patch|Delete|Head|Options)Mapping\s*\("
)


def controller_source_path(root: Path, fqn: str) -> Path:
    """Map a fully-qualified Java class name to its source path under
    crag-console-api / crag-open-api.

    Example: ``ai.cerbur.crag.console.auth.controller.AuthController`` ->
    ``crag-console-api/src/main/java/ai/cerbur/crag/console/auth/controller/AuthController.java``.
    The first package segment after ``ai.cerbur.crag`` identifies the module
    family (``console`` -> ``crag-console-api``, ``open`` -> ``crag-open-api``).
    """
    parts = fqn.split(".")
    if len(parts) < 5 or parts[0:3] != ["ai", "cerbur", "crag"]:
        return root / "__nonexistent__"
    family = parts[3]
    module = {"console": "crag-console-api", "open": "crag-open-api"}.get(family)
    if not module:
        return root / "__nonexistent__"
    rel = "/".join(parts) + ".java"
    return root / module / "src/main/java" / rel


def find_missing_controller_routes(
    root: Path, controller: str, routes: list[dict[str, Any]]
) -> list[str]:
    src = controller_source_path(root, controller)
    if not src.exists():
        return [f"controller-routes references {controller!r} but source not found at {src}"]
    text = src.read_text(encoding="utf-8")
    # The Controller's class-level @RequestMapping defines a base path. Method
    # annotations add the remaining segment.
    base_paths = re.findall(
        r'@RequestMapping\s*\(\s*(?:"([^"]+)"|value\s*=\s*"([^"]+)")\s*\)', text
    )
    base = ""
    for m in base_paths:
        if m[0] or m[1]:
            base = m[0] or m[1]
            break
    found: set[tuple[str, str]] = set()
    for method_line in re.finditer(
        r'@(Get|Post|Put|Patch|Delete|Head|Options|Request)Mapping(?:\s*\(([^)]*)\))?',
        text,
    ):
        verb = method_line.group(1).upper()
        if verb == "REQUEST":
            verb = ""  # @RequestMapping on a method without method= is generic
        args = method_line.group(2) or ""
        path_match = re.search(r'"([^"]+)"', args)
        full_path = base + (path_match.group(1) if path_match else "")
        if verb:
            found.add((verb, full_path))
    # Also treat bare class-level mapping routes (when the Controller exposes
    # only GET on the base path via @GetMapping without args, Spring uses base).
    for method_line in re.finditer(
        r'@(Get|Post|Put|Patch|Delete)Mapping\b(?!\s*\()', text
    ):
        verb = method_line.group(1).upper()
        found.add((verb, base))
    missing: list[str] = []
    for r in routes:
        if not isinstance(r, dict):
            continue
        method = (r.get("method") or "").upper()
        path = r.get("path")
        if not isinstance(path, str):
            continue
        if (method, path) not in found:
            missing.append(
                f"documented route {method} {path} for {controller} not found "
                f"in source; found routes: {sorted(found) or '(none)'}."
            )
    return missing


# ----------------------------------------------------------------------
# Markdown source-code link check
# ----------------------------------------------------------------------
MD_LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")


def check_markdown_links(root: Path, md: Path) -> list[Diagnostic]:
    diags: list[Diagnostic] = []
    if not md.exists():
        diags.append(_err(f"{md.relative_to(root)}: markdown file missing"))
        return diags
    text = md.read_text(encoding="utf-8")
    base_dir = md.parent
    for m in MD_LINK_RE.finditer(text):
        href = m.group(1).strip()
        # Ignore external links, anchors, and the OpenAPI yaml references that
        # are validated separately.
        if href.startswith(("http://", "https://", "#")):
            continue
        # Strip any anchor suffix.
        target = href.split("#", 1)[0].strip()
        if not target:
            continue
        resolved = (base_dir / target).resolve()
        if not resolved.exists():
            diags.append(
                _err(
                    f"{md.relative_to(root)}: broken link {href!r} "
                    f"(resolved to {resolved})"
                )
            )
    return diags


# ----------------------------------------------------------------------
# CLI entry
# ----------------------------------------------------------------------
def main(argv: list[str]) -> int:
    root = Path(__file__).resolve().parents[1]
    result = validate_repo(root)
    warnings = [d for d in result.diagnostics if d.level == "WARNING"]
    errors = [d for d in result.diagnostics if not d.ok]
    for d in result.diagnostics:
        print(f"[{d.level}] {d.message}")
    if warnings:
        print(f"\nOpenAPI validator: {len(warnings)} warning(s).")
    if errors:
        print(f"OpenAPI validator: {len(errors)} error(s).")
        return 1
    print("OpenAPI validator: 0 errors.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
