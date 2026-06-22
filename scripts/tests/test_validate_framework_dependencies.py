#!/usr/bin/env python3
"""
Unit tests for validate_framework_dependencies.py.

Uses temporary directories to simulate repository layouts and verifies
that each validation check correctly detects violations.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

# Add scripts directory to path so we can import the validator
SCRIPT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPT_DIR))

import validate_framework_dependencies as vld


class TestCheckRepositories(unittest.TestCase):
    """Verify milestone/snapshot repository detection."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.orig_root = vld.REPO_ROOT
        vld.REPO_ROOT = Path(self.tmp.name)

    def tearDown(self):
        vld.REPO_ROOT = self.orig_root
        self.tmp.cleanup()

    def write_file(self, relpath: str, content: str):
        p = Path(self.tmp.name) / relpath
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def test_passes_when_no_milestone_repo(self):
        self.write_file("settings.gradle.kts", """
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
""")
        errors = vld.check_repositories()
        self.assertEqual(errors, [])

    def test_detects_milestone_repo(self):
        self.write_file("build.gradle.kts", """
allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}
""")
        errors = vld.check_repositories()
        self.assertTrue(any("milestone" in e.lower() for e in errors),
                        f"Should detect milestone repo, got: {errors}")

    def test_detects_snapshot_repo(self):
        self.write_file("settings.gradle.kts", """
pluginManagement {
    repositories {
        maven { url = uri("https://repo.spring.io/snapshot") }
    }
}
""")
        errors = vld.check_repositories()
        self.assertTrue(any("snapshot" in e.lower() for e in errors),
                        f"Should detect snapshot repo, got: {errors}")


class TestCheckNoPlatformMixing(unittest.TestCase):
    """Verify no Gradle platform() manages Boot or Spring AI BOM."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.orig_root = vld.REPO_ROOT
        vld.REPO_ROOT = Path(self.tmp.name)

    def tearDown(self):
        vld.REPO_ROOT = self.orig_root
        self.tmp.cleanup()

    def write_file(self, relpath: str, content: str):
        p = Path(self.tmp.name) / relpath
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def test_passes_without_platform(self):
        self.write_file("crag-app/build.gradle.kts", """
dependencies {
    implementation(project(":crag-common"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
""")
        errors = vld.check_no_platform_mixing()
        self.assertEqual(errors, [])

    def test_detects_boot_platform(self):
        self.write_file("crag-app/build.gradle.kts", """
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
}
""")
        errors = vld.check_no_platform_mixing()
        self.assertTrue(any("platform" in e.lower() for e in errors),
                        f"Should detect platform usage, got: {errors}")

    def test_detects_spring_ai_platform(self):
        """Spring AI BOM via platform() in submodule is forbidden — dependency-management handles BOM imports."""
        self.write_file("crag-ingestion/build.gradle.kts", """
dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-commons")
}
""")
        errors = vld.check_no_platform_mixing()
        self.assertTrue(any("platform" in e.lower() for e in errors),
                        f"Should detect Spring AI BOM platform, got: {errors}")


class TestCheckAutoconfigAndDummyKeys(unittest.TestCase):
    """Verify OpenAI autoconfig exclusion and dummy keys are removed."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.orig_root = vld.REPO_ROOT
        vld.REPO_ROOT = Path(self.tmp.name)

    def tearDown(self):
        vld.REPO_ROOT = self.orig_root
        self.tmp.cleanup()

    def write_file(self, relpath: str, content: str):
        p = Path(self.tmp.name) / relpath
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def test_passes_when_clean(self):
        self.write_file("crag-app/src/main/resources/application.yml", """
spring:
  datasource:
    url: jdbc:h2:mem:test
""")
        errors = vld.check_autoconfig_and_dummy_keys()
        self.assertEqual(errors, [])

    def test_detects_openai_autoconfig(self):
        self.write_file("crag-app/src/main/resources/application.yml", """
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration
""")
        errors = vld.check_autoconfig_and_dummy_keys()
        self.assertTrue(any("OpenAI" in e for e in errors),
                        f"Should detect OpenAI autoconfig, got: {errors}")

    def test_detects_dummy_api_key(self):
        self.write_file("crag-app/src/test/resources/application.yml", """
spring:
  ai:
    openai:
      api-key: dummy-plan1-key
""")
        errors = vld.check_autoconfig_and_dummy_keys()
        self.assertTrue(any("dummy" in e.lower() for e in errors),
                        f"Should detect dummy API key, got: {errors}")


class TestCheckSpringAiBoundary(unittest.TestCase):
    """Verify Spring AI BOM is only allowed in crag-ingestion."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.orig_root = vld.REPO_ROOT
        vld.REPO_ROOT = Path(self.tmp.name)

    def tearDown(self):
        vld.REPO_ROOT = self.orig_root
        self.tmp.cleanup()

    def write_file(self, relpath: str, content: str):
        p = Path(self.tmp.name) / relpath
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def test_allows_bom_in_crag_ingestion(self):
        self.write_file("crag-ingestion/build.gradle.kts", """
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}
dependencies {
    implementation("org.springframework.ai:spring-ai-commons")
}
""")
        errors = vld.check_spring_ai_boundary()
        self.assertEqual(errors, [],
                        f"crag-ingestion should be allowed to import Spring AI BOM, got: {errors}")

    def test_detects_bom_in_root_build(self):
        """Root build.gradle.kts must NOT import Spring AI BOM globally."""
        self.write_file("build.gradle.kts", """
subprojects {
    dependencyManagement {
        imports {
            mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
        }
    }
}
""")
        errors = vld.check_spring_ai_boundary()
        self.assertTrue(len(errors) > 0,
                        f"Root build should not import Spring AI BOM globally, got: {errors}")

    def test_detects_bom_in_other_submodule(self):
        self.write_file("crag-retrieval/build.gradle.kts", """
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}
""")
        errors = vld.check_spring_ai_boundary()
        self.assertTrue(len(errors) > 0,
                        f"Non-ingestion submodule should not import Spring AI BOM, got: {errors}")


class TestCheckContractsRuntimeBoundary(unittest.TestCase):
    """Verify crag-platform-contracts has no Spring/Runtime deps,
    and crag-grpc-runtime has no Contracts or business deps."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.orig_root = vld.REPO_ROOT
        vld.REPO_ROOT = Path(self.tmp.name)

    def tearDown(self):
        vld.REPO_ROOT = self.orig_root
        self.tmp.cleanup()

    def write_file(self, relpath: str, content: str):
        p = Path(self.tmp.name) / relpath
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def test_contracts_passes_with_protobuf_only(self):
        self.write_file("crag-platform-contracts/build.gradle.kts", """
plugins {
    `java-library`
}
dependencies {
    implementation("io.grpc:grpc-stub")
    implementation("com.google.protobuf:protobuf-java")
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertEqual(errors, [])

    def test_contracts_rejects_spring_boot_plugin(self):
        self.write_file("crag-platform-contracts/build.gradle.kts", """
plugins {
    `java-library`
    alias(libs.plugins.spring.boot)
}
dependencies {
    implementation("io.grpc:grpc-stub")
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertTrue(any("Spring Boot plugin" in e for e in errors),
                        f"Should detect Spring Boot plugin, got: {errors}")

    def test_contracts_rejects_spring_boot_starter(self):
        self.write_file("crag-platform-contracts/build.gradle.kts", """
plugins {
    `java-library`
}
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertTrue(any("Spring Boot starter" in e for e in errors),
                        f"Should detect Spring Boot starter, got: {errors}")

    def test_contracts_rejects_runtime_dep(self):
        self.write_file("crag-platform-contracts/build.gradle.kts", """
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-grpc-runtime"))
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertTrue(any("crag-grpc-runtime" in e for e in errors),
                        f"Should detect runtime dep, got: {errors}")

    def test_runtime_passes_with_grpc_and_spring_context(self):
        self.write_file("crag-grpc-runtime/build.gradle.kts", """
plugins {
    `java-library`
}
dependencies {
    implementation("io.grpc:grpc-stub")
    implementation("org.springframework:spring-context")
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertEqual(errors, [])

    def test_runtime_rejects_spring_boot_plugin(self):
        self.write_file("crag-grpc-runtime/build.gradle.kts", """
plugins {
    `java-library`
    alias(libs.plugins.spring.boot)
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertTrue(any("Spring Boot plugin" in e for e in errors),
                        f"Should detect Spring Boot plugin, got: {errors}")

    def test_runtime_rejects_contracts_dep(self):
        self.write_file("crag-grpc-runtime/build.gradle.kts", """
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-platform-contracts"))
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertTrue(any("crag-platform-contracts" in e for e in errors),
                        f"Should detect contracts dep, got: {errors}")

    def test_runtime_rejects_business_module_dep(self):
        self.write_file("crag-grpc-runtime/build.gradle.kts", """
plugins {
    `java-library`
}
dependencies {
    implementation(project(":crag-storage"))
}
""")
        errors = vld.check_contracts_runtime_boundary()
        self.assertTrue(any("crag-storage" in e for e in errors),
                        f"Should detect business module dep, got: {errors}")


if __name__ == "__main__":
    unittest.main()
