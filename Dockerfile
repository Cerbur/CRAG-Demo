# syntax=docker/dockerfile:1.7

# ============================================================
# CRAG-Demo — 多阶段 Docker 构建
# Stage 1: 编译打包（JDK 21）
# Stage 2: 运行（JRE 21，非 root）
# ============================================================

# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# 先复制 Gradle 配置，利用 Docker 缓存层
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY crag-common/build.gradle.kts crag-common/build.gradle.kts
COPY crag-storage/build.gradle.kts crag-storage/build.gradle.kts
COPY crag-retrieval/build.gradle.kts crag-retrieval/build.gradle.kts
COPY crag-ingestion/build.gradle.kts crag-ingestion/build.gradle.kts
COPY crag-query/build.gradle.kts crag-query/build.gradle.kts
COPY crag-api/build.gradle.kts crag-api/build.gradle.kts
COPY crag-smoke/build.gradle.kts crag-smoke/build.gradle.kts
COPY crag-app/build.gradle.kts crag-app/build.gradle.kts
RUN --mount=type=cache,id=crag-gradle-cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew :crag-app:dependencies --no-daemon

# 复制源码并构建
COPY crag-common/src/ crag-common/src/
COPY crag-storage/src/ crag-storage/src/
COPY crag-retrieval/src/ crag-retrieval/src/
COPY crag-ingestion/src/ crag-ingestion/src/
COPY crag-query/src/ crag-query/src/
COPY crag-api/src/ crag-api/src/
COPY crag-smoke/src/ crag-smoke/src/
COPY crag-app/src/ crag-app/src/
RUN --mount=type=cache,id=crag-gradle-cache,target=/root/.gradle,sharing=locked \
    ./gradlew :crag-app:bootJar --no-daemon

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# 复制构建产物
COPY --from=builder /workspace/crag-app/build/libs/*.jar app.jar

# 非 root 运行
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
