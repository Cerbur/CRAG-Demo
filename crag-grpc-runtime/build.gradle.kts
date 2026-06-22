plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configurations.configureEach {
    resolutionStrategy {
        force("io.grpc:grpc-core:${libs.versions.grpc.get()}")
        force("io.grpc:grpc-api:${libs.versions.grpc.get()}")
    }
}

dependencies {
    implementation("org.springframework:spring-context")
    implementation(libs.grpc.api)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.protobuf.java)
    implementation("io.grpc:grpc-services:${libs.versions.grpc.get()}")
    implementation("io.grpc:grpc-core:${libs.versions.grpc.get()}")
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework:spring-context")
    testImplementation(libs.grpc.inprocess)
    testImplementation("io.grpc:grpc-testing")
    testImplementation(libs.protobuf.java)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
