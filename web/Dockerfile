# syntax=docker/dockerfile:1.7

# ============================================================
# CRAG Web Console — Node 运行时镜像（plan_22/22.9）
# 多阶段：builder 安装依赖并 `pnpm build`，runtime 只带 dist/ 与 server/。
# Runtime Server 仅用 Node 内置模块，运行镜像零生产依赖。
# 运行身份固定为非 root 的 `node` 用户（node:alpine 自带 uid 1000）。
# ============================================================

# 固定明确发行标签；Node ≥ 22.12（package.json engines 要求）。
ARG NODE_IMAGE=node:22.12.0-alpine

# --- Stage 1: build dist/ ---
FROM ${NODE_IMAGE} AS builder
WORKDIR /app
# 直接安装固定版本 pnpm，而非 corepack：node:22.12.0-alpine 自带 corepack 的内置
# 签名密钥无法验证当前 pnpm 发布签名（"Cannot find matching keyid"）。
RUN npm install -g pnpm@10.32.1
# 先复制依赖清单以利用 Docker 层缓存。
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
# 复制构建所需源（.dockerignore 排除 node_modules / dist / 测试产物）。
COPY tsconfig.json tsconfig.app.json tsconfig.node.json ./
COPY vite.config.ts vitest.config.ts playwright.config.ts eslint.config.js ./
COPY index.html ./
COPY src ./src
COPY server ./server
RUN pnpm build

# --- Stage 2: runtime ---
FROM ${NODE_IMAGE} AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    WEB_PORT=3000
# 运行镜像只含构建产物、Runtime Server 与 package 元数据（无 node_modules）。
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/server ./server
COPY package.json ./
EXPOSE 3000
# node:alpine 提供 uid 1000 的非 root `node` 用户。
USER node
# Runtime Server：静态托管 + SPA 回退 + /health + 同源代理。
CMD ["node", "server/server.mjs"]
