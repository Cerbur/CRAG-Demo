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
RUN --mount=type=cache,id=crag-gradle-cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew dependencies --no-daemon

# 复制源码并构建
COPY src/ src/
RUN --mount=type=cache,id=crag-gradle-cache,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# 复制构建产物
COPY --from=builder /workspace/build/libs/*.jar app.jar

# 非 root 运行
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
