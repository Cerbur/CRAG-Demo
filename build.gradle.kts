group = "ai.cerbur.crag"
version = "0.1.0"

val validatePlans by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates Plan documents and index consistency."
    commandLine("python3", "scripts/validate_plans.py", "--strict")
}

tasks.register("check") {
    group = "verification"
    description = "Runs root project verification."
    dependsOn(validatePlans)
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
