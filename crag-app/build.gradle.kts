plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":crag-common"))
    implementation(project(":crag-storage"))
    implementation(project(":crag-ingestion"))
    implementation(project(":crag-retrieval"))
    implementation(project(":crag-query"))
    implementation(project(":crag-api"))
    runtimeOnly(project(":crag-smoke"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

// 禁用 plain Jar，固定 Boot Jar 文件名供 Dockerfile 精确复制
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("crag-demo.jar")
}

val aiVersion = libs.versions.spring.ai.get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${aiVersion}")
    }
}
