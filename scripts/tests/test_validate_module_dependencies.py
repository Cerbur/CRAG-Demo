import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "validate_module_dependencies.py"


def load_validator():
    spec = importlib.util.spec_from_file_location(
        "validate_module_dependencies", MODULE_PATH
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Current module set after plan_16: the six RAG subprojects were consolidated into
# crag-rag-service, so only shared infrastructure modules + the five Application
# roots remain. plan_21/21.1 adds crag-rag-contracts.
SETTINGS_COMMON = """\
rootProject.name = "crag-demo"

include(
    "crag-id",
    "crag-common",
    "crag-platform-contracts",
    "crag-knowledge-contracts",
    "crag-access-contracts",
    "crag-rag-contracts",
    "crag-grpc-runtime",
    "crag-event",
    "crag-rag-service",
    "crag-access-service",
    "crag-knowledge-service",
    "crag-console-api",
    "crag-open-api"
)
"""

BUILD_NO_DEPS = """\
plugins {
    `java-library`
}
dependencies {
}
"""

# Shared infrastructure modules carry an empty whitelist — no project deps allowed.
BUILD_CONTRACTS = BUILD_NO_DEPS
BUILD_GRPC_RUNTIME = BUILD_NO_DEPS

# Application roots assemble shared modules (allowed because they are in APP_MODULES).
BUILD_RAG_SERVICE = """\
plugins {
    java
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-id"))
    implementation(project(":crag-platform-contracts"))
    implementation(project(":crag-grpc-runtime"))
}
"""

BUILD_APP_OTHER = """\
plugins {
    java
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-platform-contracts"))
    implementation(project(":crag-grpc-runtime"))
}
"""


class ValidateModuleDependenciesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()

    def _setup_repo(self, root, modules, overrides=None):
        """Create a minimal repo structure with given module build files."""
        overrides = overrides or {}
        settings_path = root / "settings.gradle.kts"
        settings_path.write_text(SETTINGS_COMMON, encoding="utf-8")
        (root / ".git").mkdir(exist_ok=True)
        defaults = {
            "crag-id": BUILD_NO_DEPS,
            "crag-common": BUILD_NO_DEPS,
            "crag-platform-contracts": BUILD_CONTRACTS,
            "crag-knowledge-contracts": BUILD_CONTRACTS,
            "crag-access-contracts": BUILD_CONTRACTS,
            "crag-rag-contracts": BUILD_CONTRACTS,
            "crag-grpc-runtime": BUILD_GRPC_RUNTIME,
            "crag-event": BUILD_NO_DEPS,
            "crag-rag-service": BUILD_RAG_SERVICE,
            "crag-access-service": BUILD_APP_OTHER,
            "crag-knowledge-service": BUILD_APP_OTHER,
            "crag-console-api": BUILD_APP_OTHER,
            "crag-open-api": BUILD_APP_OTHER,
        }
        for mod in modules:
            mod_dir = root / mod
            mod_dir.mkdir(parents=True, exist_ok=True)
            content = overrides.get(mod, defaults.get(mod, BUILD_NO_DEPS))
            (mod_dir / "build.gradle.kts").write_text(content, encoding="utf-8")

    def test_all_current_dependencies_pass(self):
        """All current module project dependencies match the whitelist."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._setup_repo(
                root,
                [
                    "crag-id",
                    "crag-common",
                    "crag-platform-contracts",
                    "crag-knowledge-contracts",
                    "crag-access-contracts",
                    "crag-rag-contracts",
                    "crag-grpc-runtime",
                    "crag-event",
                    "crag-rag-service",
                    "crag-access-service",
                    "crag-knowledge-service",
                    "crag-console-api",
                    "crag-open-api",
                ],
            )
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors, f"Unexpected errors: {errors}")

    def test_rejects_project_dep_in_infrastructure_module(self):
        """A project dependency in a whitelisted infrastructure module is rejected."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            overrides = {
                "crag-grpc-runtime": """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-rag-service"))
}
""",
            }
            self._setup_repo(
                root,
                [
                    "crag-id",
                    "crag-common",
                    "crag-platform-contracts",
                    "crag-grpc-runtime",
                    "crag-rag-service",
                ],
                overrides=overrides,
            )
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertTrue(
            any("crag-grpc-runtime" in err.message and "crag-rag-service" in err.message for err in errors),
            f"Expected error about crag-grpc-runtime→crag-rag-service, got: {errors}",
        )

    def test_rejects_dependency_cycle(self):
        """A dependency cycle between modules is detected."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Create cycle: crag-common → crag-grpc-runtime → crag-common
            settings = """\
rootProject.name = "test"
include("crag-common", "crag-grpc-runtime")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)

            common_dir = root / "crag-common"
            common_dir.mkdir(parents=True)
            (common_dir / "build.gradle.kts").write_text(
                """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-grpc-runtime"))
}
""",
                encoding="utf-8",
            )

            runtime_dir = root / "crag-grpc-runtime"
            runtime_dir.mkdir(parents=True)
            (runtime_dir / "build.gradle.kts").write_text(
                """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-common"))
}
""",
                encoding="utf-8",
            )

            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertTrue(
            any("环" in err.message for err in errors),
            f"Expected cycle error, got: {errors}",
        )

    def test_application_root_assembles_shared_modules(self):
        """Application roots (e.g. crag-rag-service) may depend on shared modules."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._setup_repo(
                root,
                [
                    "crag-id",
                    "crag-common",
                    "crag-platform-contracts",
                    "crag-grpc-runtime",
                    "crag-rag-service",
                ],
            )
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors, f"crag-rag-service assembly should pass, got: {errors}")

    def test_contracts_module_passes_with_no_deps(self):
        """crag-platform-contracts with no project dependencies passes."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = """\
rootProject.name = "test"
include("crag-platform-contracts")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)
            mod_dir = root / "crag-platform-contracts"
            mod_dir.mkdir(parents=True)
            (mod_dir / "build.gradle.kts").write_text(BUILD_NO_DEPS, encoding="utf-8")
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors)

    def test_contracts_module_rejects_shared_dep(self):
        """crag-platform-contracts must not depend on any other project module."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = """\
rootProject.name = "test"
include("crag-platform-contracts", "crag-common")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)
            mod_dir = root / "crag-platform-contracts"
            mod_dir.mkdir(parents=True)
            (mod_dir / "build.gradle.kts").write_text(
                """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-common"))
}
""",
                encoding="utf-8",
            )
            common_dir = root / "crag-common"
            common_dir.mkdir(parents=True)
            (common_dir / "build.gradle.kts").write_text(BUILD_NO_DEPS, encoding="utf-8")
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertTrue(
            any("crag-platform-contracts" in err.message for err in errors),
            f"Expected error about crag-platform-contracts, got: {errors}",
        )

    def test_access_contracts_module_rejects_shared_dep(self):
        """crag-access-contracts must not depend on any other project module (plan_20)."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = """\
rootProject.name = "test"
include("crag-access-contracts", "crag-common")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)
            mod_dir = root / "crag-access-contracts"
            mod_dir.mkdir(parents=True)
            (mod_dir / "build.gradle.kts").write_text(
                """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-common"))
}
""",
                encoding="utf-8",
            )
            common_dir = root / "crag-common"
            common_dir.mkdir(parents=True)
            (common_dir / "build.gradle.kts").write_text(BUILD_NO_DEPS, encoding="utf-8")
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertTrue(
            any("crag-access-contracts" in err.message for err in errors),
            f"Expected error about crag-access-contracts, got: {errors}",
        )

    def test_runtime_module_passes_with_no_deps(self):
        """crag-grpc-runtime with no project dependencies passes."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = """\
rootProject.name = "test"
include("crag-grpc-runtime")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)
            mod_dir = root / "crag-grpc-runtime"
            mod_dir.mkdir(parents=True)
            (mod_dir / "build.gradle.kts").write_text(BUILD_NO_DEPS, encoding="utf-8")
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors)

    def test_runtime_module_rejects_contracts_dep(self):
        """crag-grpc-runtime must not depend on crag-platform-contracts."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            settings = """\
rootProject.name = "test"
include("crag-grpc-runtime", "crag-platform-contracts")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)
            mod_dir = root / "crag-grpc-runtime"
            mod_dir.mkdir(parents=True)
            (mod_dir / "build.gradle.kts").write_text(
                """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-platform-contracts"))
}
""",
                encoding="utf-8",
            )
            contracts_dir = root / "crag-platform-contracts"
            contracts_dir.mkdir(parents=True)
            (contracts_dir / "build.gradle.kts").write_text(BUILD_NO_DEPS, encoding="utf-8")
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertTrue(
            any("crag-grpc-runtime" in err.message for err in errors),
            f"Expected error about crag-grpc-runtime, got: {errors}",
        )


if __name__ == "__main__":
    unittest.main()
