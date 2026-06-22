rootProject.name = "crag-demo"

pluginManagement {
    repositories {
        mavenCentral()
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
    "crag-platform-contracts",
    "crag-grpc-runtime",
    "crag-access-service",
    "crag-knowledge-service",
    "crag-rag-service",
    "crag-console-api",
    "crag-open-api"
)
