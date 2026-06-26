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
    implementation(project(":crag-platform-contracts"))
    implementation(project(":crag-grpc-runtime"))
    implementation(project(":crag-id"))
    implementation(project(":crag-event"))
    implementation(project(":crag-knowledge-contracts"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-anthropic")
    implementation(libs.spring.ai.commons)
    implementation("org.postgresql:postgresql")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("crag-rag-service.jar")
}

val aiVersion = libs.versions.spring.ai.get()
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${aiVersion}")
    }
}
