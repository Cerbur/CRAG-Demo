import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createServer, type Server, type ServerResponse } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { request } from 'node:http';
import { createRuntimeServer } from '../../server/server';

// 22.9 Runtime Server 行为测试（Node http，不依赖真实后端）。
// 通过可注入的 mock upstream 验证：health、静态文件、SPA fallback、双同源代理、
// 502/503、上传流不破坏、Cookie Path 重写。jsdom 环境下 Node 内置 http 仍可用。

interface UpstreamSnapshot {
  method: string | undefined;
  url: string | undefined;
  headers: Record<string, string | string[] | undefined>;
  body: Buffer;
}

type UpstreamHandler = (req: UpstreamSnapshot, res: ServerResponse) => void;

interface UpstreamHandle {
  port: number;
  close: () => Promise<void>;
  /** 最近一次收到的请求快照（用于断言代理转发的 path / headers）。 */
  last: () => UpstreamSnapshot | undefined;
}

function startUpstream(handler: UpstreamHandler): Promise<UpstreamHandle> {
  let lastReq: UpstreamSnapshot | undefined;
  const server: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => {
      // 用显式快照替代 spread，避免丢失 headers / body。
      const snapshot: UpstreamSnapshot = {
        method: req.method,
        url: req.url,
        headers: { ...req.headers },
        body: Buffer.concat(chunks),
      };
      lastReq = snapshot;
      handler(snapshot, res);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      const port = addr && typeof addr === 'object' ? addr.port : 0;
      resolve({
        port,
        last: () => lastReq,
        close: () =>
          new Promise<void>((r) => server.close(() => r())),
      });
    });
  });
}

function call(
  port: number,
  method: string,
  path: string,
  opts: { headers?: Record<string, string>; body?: Buffer } = {},
): Promise<{ status: number; headers: Record<string, string | string[] | undefined>; body: Buffer }> {
  return new Promise((resolve, reject) => {
    const req = request(
      { port, host: '127.0.0.1', method, path, headers: opts.headers },
      (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () =>
          resolve({ status: res.statusCode ?? 0, headers: res.headers, body: Buffer.concat(chunks) }),
        );
      },
    );
    req.on('error', reject);
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

let staticRoot: string;
let consoleUpstream: UpstreamHandle;
let openUpstream: UpstreamHandle;
let deadUpstreamPort: number;
let runtime: Server;
let runtimePort: number;

beforeAll(async () => {
  staticRoot = await mkStaticFixture();
  consoleUpstream = await startUpstream((req, res) => {
    const url = req.url ?? '/';
    if (url.startsWith('/api/v1/auth/refresh')) {
      res.writeHead(200, {
        'content-type': 'application/json',
        'set-cookie': 'crag_refresh=secret-stub; Path=/api/v1/auth; HttpOnly; SameSite=Strict',
      });
      res.end(JSON.stringify({ code: '0', result: { accessToken: 'stub-access' } }));
      return;
    }
    if (url === '/api/v1/ping') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    if (url === '/api/v1/unavailable') {
      res.writeHead(503, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ code: '50300' }));
      return;
    }
    if (url === '/api/v1/documents') {
      // 回显实际读到的字节数（来自流式 body），证明上传未被截断或缓冲破坏。
      res.writeHead(202, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ code: '0', result: { receivedBytes: req.body.length } }));
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ code: '40400' }));
  });
  openUpstream = await startUpstream((_req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ code: '0', result: { answer: 'a', sources: [] } }));
  });
  // 一个确定没人监听的端口，用于触发代理 502。
  deadUpstreamPort = await freePort();

  runtime = createRuntimeServer({
    consoleApiOrigin: `http://127.0.0.1:${consoleUpstream.port}`,
    openApiOrigin: `http://127.0.0.1:${openUpstream.port}`,
    staticRoot,
  });
  runtimePort = await listen(runtime);
});

afterAll(async () => {
  await new Promise<void>((r) => runtime.close(() => r()));
  await consoleUpstream.close();
  await openUpstream.close();
});

describe('runtime server /health', () => {
  it('returns 200 UP JSON without proxying', async () => {
    const res = await call(runtimePort, 'GET', '/health');
    expect(res.status).toBe(200);
    expect(JSON.parse(res.body.toString('utf8'))).toEqual({ status: 'UP' });
  });
});

describe('runtime server static + SPA fallback', () => {
  it('serves index.html at root with text/html content type', async () => {
    const res = await call(runtimePort, 'GET', '/');
    expect(res.status).toBe(200);
    expect(String(res.headers['content-type'])).toContain('text/html');
    expect(res.body.toString('utf8')).toContain('SPA fixture');
  });

  it('falls back to index.html for a deep client-side route (no extension)', async () => {
    const res = await call(runtimePort, 'GET', '/app/knowledge/kb_123');
    expect(res.status).toBe(200);
    expect(String(res.headers['content-type'])).toContain('text/html');
    expect(res.body.toString('utf8')).toContain('SPA fixture');
  });

  it('serves a real static asset with the correct content type', async () => {
    const res = await call(runtimePort, 'GET', '/assets/app.css');
    expect(res.status).toBe(200);
    expect(String(res.headers['content-type'])).toContain('text/css');
    expect(res.body.toString('utf8')).toContain('body');
  });

  it('returns 404 for a missing asset with an extension (no SPA fallback)', async () => {
    const res = await call(runtimePort, 'GET', '/assets/missing.js');
    expect(res.status).toBe(404);
  });

  it('blocks path traversal outside the static root', async () => {
    const res = await call(runtimePort, 'GET', '/../../package.json');
    // 浏览器会规范化 ../；这里验证服务端再次校验，禁止逃逸 staticRoot。
    expect([400, 403, 404]).toContain(res.status);
    expect(res.body.toString('utf8')).not.toContain('dependencies');
  });
});

describe('runtime console-api same-origin proxy', () => {
  it('forwards to the upstream with the prefix stripped and returns its body', async () => {
    const res = await call(runtimePort, 'GET', '/console-api/api/v1/ping', {
      headers: { 'x-trace-id': 't-1' },
    });
    expect(res.status).toBe(200);
    expect(JSON.parse(res.body.toString('utf8'))).toEqual({ ok: true });
    const last = consoleUpstream.last();
    expect(last?.url).toBe('/api/v1/ping');
  });

  it('rewrites Set-Cookie Path=/api/v1/auth to the /console-api scope', async () => {
    const res = await call(runtimePort, 'POST', '/console-api/api/v1/auth/refresh', {
      headers: { 'content-type': 'application/json', origin: 'http://localhost:3000' },
      body: Buffer.from('{}'),
    });
    expect(res.status).toBe(200);
    const setCookie = res.headers['set-cookie'];
    expect(setCookie).toBeDefined();
    const cookieValue = Array.isArray(setCookie) ? setCookie[0] : setCookie;
    expect(cookieValue).toContain('Path=/console-api/api/v1/auth');
    expect(cookieValue).not.toMatch(/Path=\/api\/v1\/auth/);
    expect(cookieValue).toContain('HttpOnly');
  });

  it('passes a large streaming upload body through unmodified', async () => {
    const payload = Buffer.alloc(256 * 1024, 0x61); // 256 KiB
    const res = await call(runtimePort, 'POST', '/console-api/api/v1/documents', {
      headers: {
        'content-type': 'multipart/form-data; boundary=BOUNDARY',
        'content-length': String(payload.length),
      },
      body: payload,
    });
    expect(res.status).toBe(202);
    const body = JSON.parse(res.body.toString('utf8'));
    expect(body.result.receivedBytes).toBe(payload.length);
  });

  it('returns 503 when the upstream responds 503', async () => {
    const res = await call(runtimePort, 'GET', '/console-api/api/v1/unavailable');
    expect(res.status).toBe(503);
  });
});

describe('runtime open-api same-origin proxy', () => {
  it('forwards open-api requests on a separate upstream', async () => {
    const res = await call(runtimePort, 'POST', '/open-api/api/v1/query', {
      headers: { 'content-type': 'application/json', authorization: 'Bearer crag_test' },
      body: Buffer.from(JSON.stringify({ question: 'q' })),
    });
    expect(res.status).toBe(200);
    const last = openUpstream.last();
    expect(last?.url).toBe('/api/v1/query');
    expect(last?.headers['authorization']).toBe('Bearer crag_test');
    expect(JSON.parse(res.body.toString('utf8')).code).toBe('0');
  });
});

describe('runtime proxy error handling', () => {
  it('responds 502 when the console upstream is unreachable', async () => {
    const dead = createRuntimeServer({
      consoleApiOrigin: `http://127.0.0.1:${deadUpstreamPort}`,
      openApiOrigin: `http://127.0.0.1:${openUpstream.port}`,
      staticRoot,
    });
    const port = await listen(dead);
    try {
      const res = await call(port, 'GET', '/console-api/api/v1/ping');
      expect(res.status).toBe(502);
    } finally {
      await new Promise<void>((r) => dead.close(() => r()));
    }
  });
});

// --- helpers ---

async function mkStaticFixture(): Promise<string> {
  const dir = join(tmpdir(), `crag-web-server-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  await mkdir(join(dir, 'assets'), { recursive: true });
  await writeFile(join(dir, 'index.html'), '<!doctype html><body>SPA fixture</body>');
  await writeFile(join(dir, 'assets', 'app.css'), 'body { margin: 0; }');
  return dir;
}

function listen(server: Server): Promise<number> {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      resolve(addr && typeof addr === 'object' ? addr.port : 0);
    });
  });
}

function freePort(): Promise<number> {
  // 拿一个临时监听端口后立即释放，用于构造"不可达 upstream"。
  return new Promise((resolve) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => {
      const addr = s.address();
      const port = addr && typeof addr === 'object' ? addr.port : 0;
      s.close(() => resolve(port));
    });
  });
}
