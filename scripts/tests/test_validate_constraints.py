"""Tests for validate_constraints.py — one pass + one fail per check type."""

import textwrap
import unittest
from pathlib import Path
import tempfile
import sys

# Ensure the parent directory is on sys.path so we can import the script under test.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import validate_constraints as vc


class TestCheckEntryIdentity(unittest.TestCase):
    def test_pass_identical(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            content = "# Project Index\n\nHello.\n"
            (root / "AGENTS.md").write_text(content, encoding="utf-8")
            (root / "CLAUDE.md").write_text(content, encoding="utf-8")
            diags = vc.check_entry_identity(root)
            self.assertEqual([], diags)

    def test_fail_different(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "AGENTS.md").write_text("AAA", encoding="utf-8")
            (root / "CLAUDE.md").write_text("BBB", encoding="utf-8")
            diags = vc.check_entry_identity(root)
            self.assertEqual(1, len(diags))
            self.assertEqual("ENTRY_MISMATCH", diags[0].code)

    def test_fail_crlf_vs_lf(self):
        """Same text content but different line endings must be flagged."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "AGENTS.md").write_bytes(b"# Project\r\n\r\nHello.\r\n")
            (root / "CLAUDE.md").write_bytes(b"# Project\n\nHello.\n")
            diags = vc.check_entry_identity(root)
            self.assertEqual(1, len(diags))
            self.assertEqual("ENTRY_MISMATCH", diags[0].code)

    def test_pass_identical_bytes(self):
        """Byte-identical files (same line endings) must pass."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            content = b"# Project\n\nHello.\n"
            (root / "AGENTS.md").write_bytes(content)
            (root / "CLAUDE.md").write_bytes(content)
            diags = vc.check_entry_identity(root)
            self.assertEqual([], diags)


class TestCheckLinks(unittest.TestCase):
    def test_pass_all_resolved(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "AGENTS.md").write_text(
                "详见 [Docker](./constraints/docker-structure.md)。\n", encoding="utf-8"
            )
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text("# Docker\n", encoding="utf-8")
            diags = vc.check_links(root)
            self.assertEqual([], diags)

    def test_fail_broken_link(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "AGENTS.md").write_text(
                "详见 [Ghost](./constraints/ghost.md)。\n", encoding="utf-8"
            )
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            diags = vc.check_links(root)
            self.assertGreaterEqual(len(diags), 1)
            self.assertTrue(any(d.code == "LINK_BROKEN" for d in diags))


class TestCheckComposeServices(unittest.TestCase):
    def test_pass_all_registered(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docker-compose.yml").write_text(
                textwrap.dedent("""\
                    services:
                      db:
                        image: pg
                      sidecar:
                        image: py
                """),
                encoding="utf-8",
            )
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text(
                "### 5.1 `db` — database\n### 5.2 `sidecar` — sidecar\n", encoding="utf-8"
            )
            diags = vc.check_compose_services(root)
            self.assertEqual([], diags)

    def test_fail_missing_service(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docker-compose.yml").write_text(
                textwrap.dedent("""\
                    services:
                      db:
                        image: pg
                      ghost:
                        image: ghost
                """),
                encoding="utf-8",
            )
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text(
                "### 5.1 `db` — database\n", encoding="utf-8"
            )
            diags = vc.check_compose_services(root)
            self.assertEqual(1, len(diags))
            self.assertEqual("COMPOSE_SERVICE_UNREGISTERED", diags[0].code)
            self.assertIn("ghost", diags[0].message)


class TestCheckComposeParseFailure(unittest.TestCase):
    """Compose file exists but parser yields zero services — must fail."""

    def test_fail_parse_zero_services(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docker-compose.yml").write_text(
                "services:\n  # no services here\n", encoding="utf-8"
            )
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text(
                "### 5.1 `db` — database\n", encoding="utf-8"
            )
            diags = vc.check_compose_services(root)
            self.assertGreaterEqual(len(diags), 1)
            self.assertTrue(any(d.code == "COMPOSE_PARSE_FAILED" for d in diags))


class TestCheckTerms(unittest.TestCase):
    def test_pass_clean(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "AGENTS.md").write_text("# OK\n受控架构例外\n", encoding="utf-8")
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text("# Docker\n", encoding="utf-8")
            diags = vc.check_terms(root)
            self.assertEqual([], diags)

    def test_fail_deprecated_term(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/test-style.md").write_text(
                "这是一个迁移期例外。\n", encoding="utf-8"
            )
            diags = vc.check_terms(root)
            self.assertGreaterEqual(len(diags), 1)
            self.assertTrue(any(d.code == "TERM_DEPRECATED" for d in diags))
            self.assertIn("迁移期例外", diags[0].message)

    def test_pass_crag_admin_allowed_context(self):
        """crag-admin in a compatibility notice is allowed."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/test-style.md").write_text(
                "禁止新增 `crag-admin` 模块，当前统一使用 crag-api。\n", encoding="utf-8"
            )
            diags = vc.check_terms(root)
            # Should not flag crag-admin here because the line is a compatibility notice.
            crag_admin_errors = [d for d in diags if "crag-admin" in d.message]
            self.assertEqual([], crag_admin_errors)

    def test_fail_crag_admin_bare_reference(self):
        """crag-admin used as a current module reference must be flagged."""
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/test-style.md").write_text(
                "Controller 应放在 crag-admin 模块中。\n", encoding="utf-8"
            )
            diags = vc.check_terms(root)
            crag_admin_errors = [d for d in diags if "crag-admin" in d.message]
            self.assertGreaterEqual(len(crag_admin_errors), 1)


class TestCheckTopologyScript(unittest.TestCase):
    def test_pass_exists(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            script_dir = root / "scripts" / "tests" / "http"
            script_dir.mkdir(parents=True, exist_ok=True)
            (script_dir / "platform_topology_test.sh").write_text("#!/bin/bash\n", encoding="utf-8")
            diags = vc.check_topology_script(root)
            self.assertEqual([], diags)

    def test_fail_missing(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            diags = vc.check_topology_script(root)
            self.assertEqual(1, len(diags))
            self.assertEqual("TOPOLOGY_SCRIPT_MISSING", diags[0].code)


class TestCheckInternalPortExposure(unittest.TestCase):
    def test_pass_no_ports(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docker-compose.yml").write_text(
                textwrap.dedent("""\
                    services:
                      access-service:
                        image: java
                      knowledge-service:
                        image: java
                """),
                encoding="utf-8",
            )
            diags = vc.check_internal_port_exposure(root)
            self.assertEqual([], diags)

    def test_fail_exposed_port(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "docker-compose.yml").write_text(
                textwrap.dedent("""\
                    services:
                      access-service:
                        image: java
                        ports:
                          - "9091:9091"
                """),
                encoding="utf-8",
            )
            diags = vc.check_internal_port_exposure(root)
            self.assertEqual(1, len(diags))
            self.assertEqual("INTERNAL_PORT_EXPOSED", diags[0].code)
            self.assertIn("access-service", diags[0].message)


class TestCheckTermsAppSmoke(unittest.TestCase):
    def test_fail_app_smoke_in_constraint(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-old.md").write_text(
                "启动 app-smoke 服务。\n", encoding="utf-8"
            )
            diags = vc.check_terms(root)
            app_smoke_errors = [d for d in diags if "app-smoke" in d.message]
            self.assertGreaterEqual(len(app_smoke_errors), 1)

    def test_pass_app_smoke_in_plan_context(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-old.md").write_text(
                "旧 app-smoke 已由 rag-service-smoke 替代。\n", encoding="utf-8"
            )
            diags = vc.check_terms(root)
            app_smoke_errors = [d for d in diags if "app-smoke" in d.message]
            self.assertEqual([], app_smoke_errors)


class TestCheckTopologyCriticalAssertions(unittest.TestCase):
    def test_pass_fail_used(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            script_dir = root / "scripts" / "tests" / "http"
            script_dir.mkdir(parents=True, exist_ok=True)
            (script_dir / "platform_topology_test.sh").write_text(
                'if ...; then fail "downstreamConnectivity not UP"\n', encoding="utf-8"
            )
            diags = vc.check_topology_critical_assertions(root)
            self.assertEqual([], diags)

    def test_fail_warn_used_for_downstream(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            script_dir = root / "scripts" / "tests" / "http"
            script_dir.mkdir(parents=True, exist_ok=True)
            (script_dir / "platform_topology_test.sh").write_text(
                'warn "Console API downstreamConnectivity not found in health response"\n',
                encoding="utf-8",
            )
            diags = vc.check_topology_critical_assertions(root)
            warn_errors = [d for d in diags if "TOPOLOGY_WARN_ON_CRITICAL" in d.code]
            self.assertGreaterEqual(len(warn_errors), 1)

    def test_pass_no_downstream_reference(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            script_dir = root / "scripts" / "tests" / "http"
            script_dir.mkdir(parents=True, exist_ok=True)
            (script_dir / "platform_topology_test.sh").write_text(
                'pass "all healthy"\n', encoding="utf-8"
            )
            diags = vc.check_topology_critical_assertions(root)
            self.assertEqual([], diags)


class TestCheckDockerPersistencePath(unittest.TestCase):
    def test_pass_pgdata_platform(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text(
                "- 本地数据库数据持久化到 `data/pgdata-platform/`\n", encoding="utf-8"
            )
            diags = vc.check_docker_persistence_path(root)
            self.assertEqual([], diags)

    def test_fail_old_pgdata_path(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text(
                "- 本地数据库数据持久化到 `data/pgdata/`\n", encoding="utf-8"
            )
            diags = vc.check_docker_persistence_path(root)
            drift_errors = [d for d in diags if "DOCKER_PERSISTENCE_DRIFT" in d.code]
            self.assertGreaterEqual(len(drift_errors), 1)

    def test_pass_old_path_in_rollback_context(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "constraints").mkdir(parents=True, exist_ok=True)
            (root / "constraints/docker-structure.md").write_text(
                "- 旧 `data/pgdata/` 仅保留回滚\n", encoding="utf-8"
            )
            diags = vc.check_docker_persistence_path(root)
            self.assertEqual([], diags)


if __name__ == "__main__":
    unittest.main()
