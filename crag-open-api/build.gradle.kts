plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":crag-platform-contracts"))
    implementation(project(":crag-access-contracts"))
    implementation(project(":crag-rag-contracts"))
    implementation(project(":crag-grpc-runtime"))
    implementation(project(":crag-common"))
    implementation(project(":crag-event"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    // 进程内 gRPC 组件测试（AccessApiKeyClientTest / RagQueryClientTest）
    testImplementation(libs.grpc.inprocess)
    // 架构测试断言"无 JPA / Spring Data Repository"；版本对齐 crag-event → spring-boot-starter-data-redis
    // 传递引入的 spring-data-commons:4.1.0，避免与运行时类路径冲突。
    testImplementation("org.springframework.data:spring-data-commons:4.1.0")
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") { enabled = false }
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("crag-open-api.jar")
}
