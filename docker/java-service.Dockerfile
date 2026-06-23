# syntax=docker/dockerfile:1.7

# ============================================================
# CRAG-Demo — 通用 Java Service 多阶段 Docker 构建
# 参数: SERVICE_MODULE — Gradle 模块名 (如 crag-rag-service)
# ============================================================

ARG SERVICE_MODULE

# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk AS builder

ARG SERVICE_MODULE

WORKDIR /workspace

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY crag-id/build.gradle.kts crag-id/build.gradle.kts
COPY crag-common/build.gradle.kts crag-common/build.gradle.kts
COPY crag-storage/build.gradle.kts crag-storage/build.gradle.kts
COPY crag-retrieval/build.gradle.kts crag-retrieval/build.gradle.kts
COPY crag-ingestion/build.gradle.kts crag-ingestion/build.gradle.kts
COPY crag-query/build.gradle.kts crag-query/build.gradle.kts
COPY crag-api/build.gradle.kts crag-api/build.gradle.kts
COPY crag-smoke/build.gradle.kts crag-smoke/build.gradle.kts
COPY crag-platform-contracts/build.gradle.kts crag-platform-contracts/build.gradle.kts
COPY crag-grpc-runtime/build.gradle.kts crag-grpc-runtime/build.gradle.kts
COPY crag-access-service/build.gradle.kts crag-access-service/build.gradle.kts
COPY crag-knowledge-service/build.gradle.kts crag-knowledge-service/build.gradle.kts
COPY crag-rag-service/build.gradle.kts crag-rag-service/build.gradle.kts
COPY crag-console-api/build.gradle.kts crag-console-api/build.gradle.kts
COPY crag-open-api/build.gradle.kts crag-open-api/build.gradle.kts
RUN --mount=type=cache,id=crag-gradle-cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew ":${SERVICE_MODULE}:dependencies" --no-daemon

COPY crag-id/src/ crag-id/src/
COPY crag-common/src/ crag-common/src/
COPY crag-storage/src/ crag-storage/src/
COPY crag-retrieval/src/ crag-retrieval/src/
COPY crag-ingestion/src/ crag-ingestion/src/
COPY crag-query/src/ crag-query/src/
COPY crag-api/src/ crag-api/src/
COPY crag-smoke/src/ crag-smoke/src/
COPY crag-platform-contracts/src/ crag-platform-contracts/src/
COPY crag-grpc-runtime/src/ crag-grpc-runtime/src/
COPY crag-access-service/src/ crag-access-service/src/
COPY crag-knowledge-service/src/ crag-knowledge-service/src/
COPY crag-rag-service/src/ crag-rag-service/src/
COPY crag-console-api/src/ crag-console-api/src/
COPY crag-open-api/src/ crag-open-api/src/
RUN --mount=type=cache,id=crag-gradle-cache,target=/root/.gradle,sharing=locked \
    ./gradlew ":${SERVICE_MODULE}:bootJar" --no-daemon

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG SERVICE_MODULE

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /workspace/${SERVICE_MODULE}/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
