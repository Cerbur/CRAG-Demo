group = "ai.cerbur.crag"
version = "0.1.0"

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
