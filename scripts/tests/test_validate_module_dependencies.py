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


SETTINGS_COMMON = """\
rootProject.name = "crag-demo"

include(
    "crag-common",
    "crag-storage",
    "crag-retrieval",
    "crag-ingestion",
    "crag-query",
    "crag-api",
    "crag-rag-service"
)
"""

BUILD_NO_DEPS = """\
plugins {
    `java-library`
}
dependencies {
}
"""

BUILD_STORAGE = """\
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
"""

BUILD_RETRIEVAL = """\
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-storage"))
}
"""

BUILD_INGESTION = """\
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-storage"))
    implementation(project(":crag-retrieval"))
}
"""

BUILD_QUERY = """\
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-retrieval"))
}
"""

BUILD_API = """\
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-ingestion"))
    implementation(project(":crag-query"))
}
"""

BUILD_APP = """\
plugins {
    java
}
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-storage"))
    implementation(project(":crag-ingestion"))
    implementation(project(":crag-retrieval"))
    implementation(project(":crag-query"))
    implementation(project(":crag-api"))
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
            "crag-common": BUILD_NO_DEPS,
            "crag-storage": BUILD_STORAGE,
            "crag-retrieval": BUILD_RETRIEVAL,
            "crag-ingestion": BUILD_INGESTION,
            "crag-query": BUILD_QUERY,
            "crag-api": BUILD_API,
            "crag-rag-service": BUILD_APP,
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
                    "crag-common",
                    "crag-storage",
                    "crag-retrieval",
                    "crag-ingestion",
                    "crag-query",
                    "crag-api",
                    "crag-rag-service",
                ],
            )
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors, f"Unexpected errors: {errors}")

    def test_rejects_unauthorized_dependency(self):
        """A dependency not in the whitelist is rejected."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            overrides = {
                "crag-query": """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-retrieval"))
    implementation(project(":crag-storage"))
}
""",
            }
            self._setup_repo(
                root,
                [
                    "crag-common",
                    "crag-storage",
                    "crag-retrieval",
                    "crag-ingestion",
                    "crag-query",
                    "crag-api",
                    "crag-rag-service",
                ],
                overrides=overrides,
            )
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertTrue(
            any("crag-query" in err.message and "crag-storage" in err.message for err in errors),
            f"Expected error about crag-query→crag-storage, got: {errors}",
        )

    def test_rejects_dependency_cycle(self):
        """A dependency cycle between modules is detected."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Create cycle: crag-common → crag-storage → crag-common
            settings = """\
rootProject.name = "test"
include("crag-common", "crag-storage")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)

            common_dir = root / "crag-common"
            common_dir.mkdir(parents=True)
            (common_dir / "build.gradle.kts").write_text(
                """\
plugins { `java-library` }
dependencies {
    implementation(project(":crag-storage"))
}
""",
                encoding="utf-8",
            )

            storage_dir = root / "crag-storage"
            storage_dir.mkdir(parents=True)
            (storage_dir / "build.gradle.kts").write_text(
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

    def test_accepts_unknown_module_in_whitelist_as_no_error(self):
        """Modules that don't exist (like crag-api, crag-smoke) don't cause errors."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Only crag-common exists, no crag-api/crag-smoke
            settings = """\
rootProject.name = "test"
include("crag-common")
"""
            (root / "settings.gradle.kts").write_text(settings, encoding="utf-8")
            (root / ".git").mkdir(exist_ok=True)
            common_dir = root / "crag-common"
            common_dir.mkdir(parents=True)
            (common_dir / "build.gradle.kts").write_text(BUILD_NO_DEPS, encoding="utf-8")

            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors)

    def test_accepts_crag_api_whitelist_directly(self):
        """crag-api module matches the whitelist entry directly (no name mapping)."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._setup_repo(
                root,
                [
                    "crag-common",
                    "crag-ingestion",
                    "crag-query",
                    "crag-api",
                ],
            )
            diagnostics = self.validator.validate(root)
        errors = [item for item in diagnostics if item.level == "ERROR"]
        self.assertEqual([], errors)

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

    def test_contracts_module_rejects_spring_dep(self):
        """crag-platform-contracts must not depend on any business module."""
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
