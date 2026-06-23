plugins {
    `java-library`
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
    implementation(project(":crag-id"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
