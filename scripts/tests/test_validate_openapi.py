"""Unit tests for scripts/validate_openapi.py (plan_21/21.12).

The validator is intentionally zero-dependency (stdlib only): PyYAML is not
installed in this environment, so the OpenAPI YAML documents are written as
YAML/JSON superset documents that ``json`` can parse while remaining valid
OpenAPI 3.1 ``.yaml`` files consumable by openapi-generator and similar tools.

Each test exercises one check the plan requires:

  1. YAML/JSON parse
  2. openapi == "3.1.x"
  3. operationId uniqueness within a document
  4. $ref resolvability
  5. example matches schema (type-level, structural)
  6. route-list drift (documented routes == implemented routes)
  7. relative source-code links resolve (Console/Open code references)
"""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

VALIDATOR_PATH = Path(__file__).parents[1] / "validate_openapi.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_openapi", VALIDATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json_doc(path: Path, doc: dict) -> None:
    """Write a JSON document; JSON is a valid YAML 1.2 subset, so the .yaml
    file is consumable by standard OpenAPI tooling while remaining stdlib-parsed.
    """
    path.write_text(json.dumps(doc, indent=2, ensure_ascii=False), encoding="utf-8")


class ValidateOpenApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()
        cls.repo_root = Path(__file__).resolve().parents[2]

    def _seed_docs_root(self) -> Path:
        """Create a temp repo skeleton with the docs/api/ dir, an empty
        docs/README.md and docs/api/README.md, and return the root Path."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "docs" / "api").mkdir(parents=True)
        (root / "docs" / "README.md").write_text("# Docs\n", encoding="utf-8")
        (root / "docs" / "api" / "README.md").write_text("# API\n", encoding="utf-8")
        return root

    def _write_basic(self, root: Path, console: dict, open_: dict) -> None:
        write_json_doc(root / "docs" / "api" / "console-api.openapi.yaml", console)
        write_json_doc(root / "docs" / "api" / "open-api.openapi.yaml", open_)

    # ------------------------------------------------------------------
    # Happy path: real documents in docs/api/ must validate end-to-end.
    # ------------------------------------------------------------------
    def test_real_documents_pass(self):
        result = self.validator.validate_repo(self.repo_root)
        self.assertTrue(
            result.ok, msg="\n".join(d.message for d in result.diagnostics if not d.ok)
        )

    # ------------------------------------------------------------------
    # 1. Parse / 2. openapi 3.1
    # ------------------------------------------------------------------
    def test_rejects_non_openapi31_version(self):
        root = self._seed_docs_root()
        self._write_basic(
            root,
            {"openapi": "3.0.0", "paths": {}, "components": {"schemas": {}}},
            {"openapi": "3.1.0", "paths": {}, "components": {"schemas": {}}},
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)
        self.assertTrue(any("3.1" in d.message for d in result.diagnostics if not d.ok))

    def test_rejects_unparseable_json(self):
        root = self._seed_docs_root()
        (root / "docs" / "api" / "console-api.openapi.yaml").write_text(
            "{ not valid json ", encoding="utf-8"
        )
        write_json_doc(
            root / "docs" / "api" / "open-api.openapi.yaml",
            {"openapi": "3.1.0", "paths": {}, "components": {"schemas": {}}},
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)

    # ------------------------------------------------------------------
    # 3. operationId uniqueness
    # ------------------------------------------------------------------
    def test_rejects_duplicate_operation_id(self):
        root = self._seed_docs_root()
        doc = {
            "openapi": "3.1.0",
            "info": {"title": "t", "version": "1"},
            "paths": {
                "/a": {
                    "get": {"operationId": "dup", "responses": {"200": {"description": "ok"}}},
                    "post": {"operationId": "dup", "responses": {"200": {"description": "ok"}}},
                }
            },
            "components": {"schemas": {}},
        }
        self._write_basic(
            root,
            doc,
            {"openapi": "3.1.0", "paths": {}, "components": {"schemas": {}}},
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)
        self.assertTrue(
            any(
                "operationId" in d.message and "dup" in d.message
                for d in result.diagnostics
                if not d.ok
            )
        )

    # ------------------------------------------------------------------
    # 4. $ref resolvability
    # ------------------------------------------------------------------
    def test_rejects_broken_ref(self):
        root = self._seed_docs_root()
        doc = {
            "openapi": "3.1.0",
            "info": {"title": "t", "version": "1"},
            "paths": {
                "/a": {
                    "get": {
                        "operationId": "op",
                        "responses": {
                            "200": {
                                "description": "ok",
                                "content": {
                                    "application/json": {
                                        "schema": {
                                            "$ref": "#/components/schemas/Missing"
                                        }
                                    }
                                },
                            }
                        },
                    }
                }
            },
            "components": {"schemas": {"Present": {"type": "object"}}},
        }
        self._write_basic(
            root,
            doc,
            {"openapi": "3.1.0", "paths": {}, "components": {"schemas": {}}},
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)
        self.assertTrue(any("$ref" in d.message for d in result.diagnostics if not d.ok))

    # ------------------------------------------------------------------
    # 5. example matches schema (structural type check, nested)
    # ------------------------------------------------------------------
    def test_rejects_example_schema_mismatch(self):
        root = self._seed_docs_root()
        doc = {
            "openapi": "3.1.0",
            "info": {"title": "t", "version": "1"},
            "paths": {
                "/a": {
                    "get": {
                        "operationId": "op",
                        "responses": {
                            "200": {
                                "description": "ok",
                                "content": {
                                    "application/json": {
                                        "schema": {
                                            "type": "object",
                                            "properties": {
                                                "code": {"type": "integer"}
                                            },
                                        },
                                        "example": {"code": "not-an-integer"},
                                    }
                                },
                            }
                        },
                    }
                }
            },
            "components": {"schemas": {}},
        }
        self._write_basic(
            root,
            doc,
            {"openapi": "3.1.0", "paths": {}, "components": {"schemas": {}}},
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)
        self.assertTrue(
            any(
                "example" in d.message.lower()
                for d in result.diagnostics
                if not d.ok
            )
        )

    # ------------------------------------------------------------------
    # 6. route-list drift: documented routes must equal implemented routes.
    # ------------------------------------------------------------------
    def test_rejects_route_list_drift(self):
        root = self._seed_docs_root()
        # Build a synthetic Console controller that declares only one route.
        ctrl_dir = (
            root
            / "crag-console-api"
            / "src/main/java/ai/cerbur/crag/console/auth/controller"
        )
        ctrl_dir.mkdir(parents=True)
        (ctrl_dir / "AuthController.java").write_text(
            'package ai.cerbur.crag.console.auth.controller;\n'
            '@RestController\n'
            '@RequestMapping("/api/v1/auth")\n'
            'public class AuthController {\n'
            '  @PostMapping("/register")\n'
            '  public Object register() { return null; }\n'
            '}\n',
            encoding="utf-8",
        )
        doc = {
            "openapi": "3.1.0",
            "info": {"title": "Console API", "version": "1"},
            "paths": {
                "/api/v1/auth/register": {
                    "post": {
                        "operationId": "register",
                        "responses": {"200": {"description": "ok"}},
                    }
                }
            },
            "components": {"schemas": {}},
            "x-crag-implementation": {
                "controller-routes": [
                    {
                        "controller": "ai.cerbur.crag.console.auth.controller.AuthController",
                        "routes": [
                            {"method": "POST", "path": "/api/v1/auth/register"},
                            {"method": "POST", "path": "/api/v1/invented/route"},
                        ],
                    }
                ]
            },
        }
        self._write_basic(
            root,
            doc,
            {
                "openapi": "3.1.0",
                "paths": {},
                "components": {"schemas": {}},
                "x-crag-implementation": {"controller-routes": []},
            },
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)
        self.assertTrue(
            any(
                "invented" in d.message or "drift" in d.message.lower()
                for d in result.diagnostics
                if not d.ok
            ),
            msg="\n".join(d.message for d in result.diagnostics if not d.ok),
        )

    # ------------------------------------------------------------------
    # 7. relative source-code links resolve from docs/api/README.md
    # ------------------------------------------------------------------
    def test_rejects_broken_source_link(self):
        root = self._seed_docs_root()
        self._write_basic(
            root,
            {
                "openapi": "3.1.0",
                "paths": {},
                "components": {"schemas": {}},
                "x-crag-implementation": {"controller-routes": []},
            },
            {
                "openapi": "3.1.0",
                "paths": {},
                "components": {"schemas": {}},
                "x-crag-implementation": {"controller-routes": []},
            },
        )
        (root / "docs" / "api" / "README.md").write_text(
            "# API\n\n[code](../../does/not/exist.java)\n", encoding="utf-8"
        )
        result = self.validator.validate_repo(root)
        self.assertFalse(result.ok)
        self.assertTrue(
            any("link" in d.message.lower() for d in result.diagnostics if not d.ok)
        )


if __name__ == "__main__":
    unittest.main()
