rootProject.name = "crag-demo"

pluginManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
        gradlePluginPortal()
    }
}

include(
    "crag-common",
    "crag-storage",
    "crag-ingestion",
    "crag-retrieval",
    "crag-query",
    "crag-api",
    "crag-smoke",
    "crag-app"
)
