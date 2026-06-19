plugins {
    id("com.diffplug.spotless") version "8.7.0" apply false
}

group = "ai.cerbur.crag"
version = "0.1.0"

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

tasks.register("check") {
    group = "verification"
    description = "Runs root project verification."
    dependsOn(validatePlans, validateModuleDependencies, validateConstraints, subprojects.map { "${it.path}:check" })
}

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
