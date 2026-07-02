// CRAG Web Console runtime server (plan_22/22.9).
//
// 同源运行时：托管 Vite 构建产物、SPA 回退、/health，以及把
// /console-api/<path> 与 /open-api/<path> 反向代理到 console-api / open-api。
// 代理会重写 Set-Cookie 的 Path=/api/v1/auth 为 /<prefix>/api/v1/auth，
// 让浏览器在 /console-api 同源前缀下回送 Refresh Cookie。
//
// 设计原则：
//   - 不承载业务逻辑，只做静态托管、回退、代理与 Cookie Path 重写。
//   - 上游通过环境变量注入（CONSOLE_API_ORIGIN / OPEN_API_ORIGIN），
//     方便测试时替换为 mock upstream。
//   - 日志只记录 method/path/status/traceId，不打印 Authorization、Cookie 或响应体。

import { createServer } from 'node:http';
import { request as httpRequest } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const INDEX_FILE = 'index.html';

const MIME_BY_EXTENSION = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
  '.webp': 'image/webp',
};

// Hop-by-hop headers must not be forwarded across the proxy (RFC 7230 §6.1).
const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'proxy-connection',
]);

const DEFAULT_PORT = 3000;
const DEFAULT_CONSOLE_ORIGIN = 'http://console-api:8080';
const DEFAULT_OPEN_ORIGIN = 'http://open-api:8081';

/**
 * 把 Set-Cookie 的 Path=/api/... 重写为 Path=/<prefix>/api/...，
 * 使 cookie 作用域对齐同源代理前缀（浏览器才会回送）。
 */
function rewriteSetCookiePath(value, prefix) {
  if (!value) return value;
  // 只重写形如 Path=/api 且后接 / ; 或行尾 的属性，保留其余指令。
  return value.replace(/(Path=)(\/api)(?=[/;]|$)/gi, `$1/${prefix}$2`);
}

function sanitizeHeaders(headers) {
  const next = {};
  for (const [key, value] of Object.entries(headers)) {
    if (HOP_BY_HOP.has(key.toLowerCase())) continue;
    next[key] = value;
  }
  return next;
}

function log(method, urlPath, status, extra) {
  const trace = extra?.traceId ? ` trace=${extra.traceId}` : '';
  // 仅记录方法、路径、状态码和 traceId；绝不打印 header 值或 body。
  console.log(`[crag-web] ${method} ${urlPath} -> ${status}${trace}`);
}

/**
 * 创建运行时 HTTP 服务器（未监听）。options 注入上游 origin 与静态根目录，
 * 便于测试用 mock upstream 替换。
 */
export function createRuntimeServer(options) {
  const consoleApiOrigin = options.consoleApiOrigin;
  const openApiOrigin = options.openApiOrigin;
  const staticRoot = options.staticRoot;
  if (!consoleApiOrigin || !openApiOrigin || !staticRoot) {
    throw new Error('createRuntimeServer 需要 consoleApiOrigin、openApiOrigin 与 staticRoot');
  }

  return createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://runtime.local');
    const pathName = url.pathname;

    if (pathName === '/health') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ status: 'UP' }));
      log('GET', '/health', 200);
      return;
    }

    if (pathName === '/console-api' || pathName.startsWith('/console-api/')) {
      proxy(req, res, consoleApiOrigin, 'console-api');
      return;
    }

    if (pathName === '/open-api' || pathName.startsWith('/open-api/')) {
      proxy(req, res, openApiOrigin, 'open-api');
      return;
    }

    serveStatic(req, res, staticRoot, url).catch((error) => {
      log('GET', pathName, 500);
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('Internal Server Error');
      }
      console.error('[crag-web] static serve error:', error instanceof Error ? error.message : String(error));
    });
  });
}

function proxy(req, res, origin, prefix) {
  const incomingUrl = new URL(req.url ?? '/', origin);
  const prefixWithSlash = '/' + prefix;
  const stripped = incomingUrl.pathname.slice(prefixWithSlash.length); // 去掉 /<prefix> 前缀
  const strippedPath = stripped.startsWith('/') ? stripped : '/' + stripped;
  const targetPath = strippedPath + (incomingUrl.search || '');
  const upstream = new URL(origin);

  const forwardHeaders = sanitizeHeaders(req.headers);
  forwardHeaders.host = upstream.host; // 让后端按自身 Host 处理 CORS / 路由

  const proxyReq = httpRequest(
    {
      method: req.method,
      hostname: upstream.hostname,
      port: upstream.port,
      path: targetPath,
      headers: forwardHeaders,
    },
    (proxyRes) => {
      const headers = sanitizeHeaders(proxyRes.headers);
      if (Array.isArray(headers['set-cookie'])) {
        headers['set-cookie'] = headers['set-cookie'].map((v) => rewriteSetCookiePath(v, prefix));
      }
      res.writeHead(proxyRes.statusCode ?? 200, headers);
      proxyRes.pipe(res);
      const traceId = typeof headers['x-trace-id'] === 'string' ? headers['x-trace-id'] : undefined;
      log(req.method ?? 'GET', incomingUrl.pathname, proxyRes.statusCode ?? 200, { traceId });
    },
  );

  proxyReq.on('error', (error) => {
    // 上游不可达（ECONNREFUSED 等）映射为 502；不泄漏内部错误细节。
    console.error('[crag-web] proxy error:', error instanceof Error ? error.message : String(error));
    if (!res.headersSent) {
      res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ code: '50200', message: 'upstream unavailable' }));
    }
    log(req.method ?? 'GET', incomingUrl.pathname, 502);
  });

  // 直接把请求体流式转发，保证 multipart 上传不被缓冲或改写。
  req.pipe(proxyReq);
}

async function serveStatic(req, res, staticRoot, url) {
  const decoded = decodeURIComponent(url.pathname);
  // 先规范化再校验必须落在 staticRoot 之内，杜绝目录穿越。
  const resolved = normalize(join(staticRoot, decoded));
  if (resolved !== staticRoot && !resolved.startsWith(staticRoot + '/')) {
    res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Forbidden');
    log('GET', url.pathname, 403);
    return;
  }

  try {
    const content = await readFile(resolved);
    const type = MIME_BY_EXTENSION[extname(resolved).toLowerCase()] ?? 'application/octet-stream';
    res.writeHead(200, { 'content-type': type });
    res.end(content);
    log('GET', url.pathname, 200);
    return;
  } catch {
    // 文件不存在：扩展名请求视为缺失静态资源（404）；其余路径回退到 SPA。
    if (extname(decoded) !== '') {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('Not Found');
      log('GET', url.pathname, 404);
      return;
    }
    try {
      const indexContent = await readFile(join(staticRoot, INDEX_FILE));
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(indexContent);
      log('GET', url.pathname, 200);
    } catch {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('Not Found');
      log('GET', url.pathname, 404);
    }
  }
}

/**
 * 从环境变量读取配置并启动服务器（pnpm start 入口）。
 */
export async function startRuntimeFromEnv() {
  const port = Number(process.env.WEB_PORT ?? DEFAULT_PORT);
  const consoleApiOrigin = process.env.CONSOLE_API_ORIGIN ?? DEFAULT_CONSOLE_ORIGIN;
  const openApiOrigin = process.env.OPEN_API_ORIGIN ?? DEFAULT_OPEN_ORIGIN;
  // 默认静态根 = 构建产物 dist/（相对仓库 web/ 目录，即 server.mjs 的上一级）。
  const defaultStaticRoot = fileURLToPath(new URL('../dist', import.meta.url));
  const staticRoot = process.env.WEB_STATIC_ROOT ?? defaultStaticRoot;

  const server = createRuntimeServer({ consoleApiOrigin, openApiOrigin, staticRoot });

  await new Promise((resolve) => {
    server.listen(port, () => resolve(undefined));
  });

  console.log(`[crag-web] runtime server listening on :${port}`);
  console.log(`[crag-web] console-api -> ${consoleApiOrigin}`);
  console.log(`[crag-web] open-api    -> ${openApiOrigin}`);
  console.log(`[crag-web] static root -> ${staticRoot}`);

  const shutdown = (signal) => {
    console.log(`[crag-web] ${signal} received, shutting down`);
    server.close(() => process.exit(0));
    // 兜底：若优雅关闭卡住，强制退出。
    setTimeout(() => process.exit(1), 10_000).unref();
  };
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));

  return server;
}

// 直接运行（pnpm start / node server/server.mjs）时启动；被 import 时不启动。
const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  startRuntimeFromEnv().catch((error) => {
    console.error('[crag-web] failed to start runtime server:', error);
    process.exit(1);
  });
}
