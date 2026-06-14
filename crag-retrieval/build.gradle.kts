plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.1"))
    api(project(":crag-common"))
    api(project(":crag-storage"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
