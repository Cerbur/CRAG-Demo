plugins {
    `java-base`
    id("com.diffplug.spotless") version "8.7.0" apply false
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.protobuf) apply false
}

group = "ai.cerbur.crag"
version = "0.1.0"

val bootVersion = libs.versions.spring.boot.get()
val grpcVersion: String = libs.versions.grpc.get().toString()
val protobufVersion: String = libs.versions.protobuf.version.get().toString()

val validatePlans by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates Plan documents and index consistency."
    commandLine("python3", "scripts/validate_plans.py", "--strict")
}

val validateModuleDependencies by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates Gradle project dependency whitelist and detects cycles."
    commandLine("python3", "scripts/validate_module_dependencies.py")
}

val validateConstraints by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates constraint document drift: entry identity, links, Compose services, and terms."
    commandLine("python3", "scripts/validate_constraints.py")
}

val validateFrameworkDependencies by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates framework version catalog, BOM, module boundaries and forbids old versions."
    commandLine("python3", "scripts/validate_framework_dependencies.py")
}

tasks.named("check") {
    group = "verification"
    description = "Runs root project verification."
    dependsOn(validatePlans, validateModuleDependencies, validateConstraints, validateFrameworkDependencies, subprojects.map { "${it.path}:check" })
}

allprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.spring.dependency-management")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${bootVersion}")
        }
    }

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.grpc") {
                useVersion(grpcVersion)
            }
            if (requested.group == "com.google.protobuf") {
                useVersion(protobufVersion)
            }
        }
    }
}
