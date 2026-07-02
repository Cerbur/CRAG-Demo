// Type declarations for the runtime server entry (web/server/server.mjs).
// 实现是纯 Node ESM（.mjs），不参与 tsc 类型生成；本声明为 import 方提供类型。

import type { Server } from 'node:http';

export interface RuntimeServerOptions {
  /** Console API 上游 origin，例如 http://console-api:8080 或测试 mock。 */
  consoleApiOrigin: string;
  /** Open API 上游 origin，例如 http://open-api:8081 或测试 mock。 */
  openApiOrigin: string;
  /** Vite 构建产物根目录，用于静态托管与 SPA 回退。 */
  staticRoot: string;
}

/**
 * 创建运行时 HTTP 服务器（未监听）。调用方负责 listen / close。
 * 处理 /health、静态托管、SPA 回退与 /console-api、/open-api 同源代理。
 */
export function createRuntimeServer(options: RuntimeServerOptions): Server;

/** 从环境变量读取配置并启动（pnpm start 入口），注册 SIGTERM/SIGINT 优雅停止。 */
export function startRuntimeFromEnv(): Promise<Server>;
