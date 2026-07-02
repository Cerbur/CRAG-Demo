import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function readJson(path: string): unknown {
  return JSON.parse(readFileSync(join(root, path), 'utf8')) as unknown;
}

describe('project config', () => {
  it('package.json declares required scripts', () => {
    const pkg = readJson('package.json') as { scripts: Record<string, string> };
    const required = ['dev', 'build', 'start', 'lint', 'typecheck', 'test', 'e2e'];
    for (const script of required) {
      expect(pkg.scripts[script], `missing script: ${script}`).toBeTypeOf('string');
      expect(pkg.scripts[script]!.length).toBeGreaterThan(0);
    }
  });

  it('tsconfig.app enables strict mode', () => {
    const tsconfig = readJson('tsconfig.app.json') as {
      compilerOptions: { strict?: boolean };
    };
    expect(tsconfig.compilerOptions.strict).toBe(true);
  });

  it('pnpm-lock.yaml exists and was committed', () => {
    expect(existsSync(join(root, 'pnpm-lock.yaml'))).toBe(true);
  });
});
