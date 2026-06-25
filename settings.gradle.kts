rootProject.name = "crag-demo"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(
    "crag-id",
    "crag-common",
    "crag-platform-contracts",
    "crag-grpc-runtime",
    "crag-event",
    "crag-access-service",
    "crag-knowledge-service",
    "crag-rag-service",
    "crag-console-api",
    "crag-open-api"
)
