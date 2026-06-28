plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":crag-knowledge-contracts"))
    implementation(project(":crag-rag-contracts"))
    implementation(project(":crag-platform-contracts"))
    implementation(project(":crag-grpc-runtime"))
    implementation(project(":crag-common"))
    implementation(project(":crag-event"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }

tasks.test {
    // plan_18 引入多个 @SpringBootTest 上下文；增大 Spring Test 上下文缓存上限，
    // 避免共享上下文被驱逐→关闭→重启（gRPC Server 单次使用，重启会抛 "Already started"）。
    systemProperty("spring.test.context.cache.maxSize", "20")
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("crag-knowledge-service.jar")
}
