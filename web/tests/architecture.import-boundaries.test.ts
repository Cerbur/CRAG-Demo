import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const root = process.cwd();
const SRC = join(root, 'src');

function listFiles(dir: string, exts: string[]): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...listFiles(full, exts));
    } else if (exts.some((e) => entry.name.endsWith(e))) {
      out.push(full);
    }
  }
  return out;
}

function readImports(filePath: string): string[] {
  const src = readFileSync(filePath, 'utf8');
  const imports: string[] = [];
  const re = /(?:import|export)\s[^'";]*?from\s*['"]([^'"]+)['"]/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    imports.push(m[1]!);
  }
  return imports;
}

const isViewLayer = (absPath: string): boolean => {
  const rel = relative(SRC, absPath);
  return (
    rel.startsWith(`pages${sep}`) ||
    rel.includes(`${sep}components${sep}`) ||
    rel.startsWith(`features${sep}`)
  );
};

const isHttpTransport = (spec: string): boolean =>
  // relative or alias path ending in services/http
  /services\/http/.test(spec) || /^@services\/http(\/|$)/.test(spec);

const NON_FEATURE_TOP_DIRS = new Set([
  'shared',
  'entities',
  'services',
  'app',
  'pages',
  'test',
]);

const isCrossFeatureInternal = (importerAbs: string, spec: string): boolean => {
  const rel = relative(SRC, importerAbs).split(sep).join('/');
  if (!rel.startsWith('features/')) return false;
  const importerFeature = rel.split('/')[1];
  if (!importerFeature) return false;

  // Alias form @features/<other>/... where <other> != importerFeature
  const aliasMatch = /^@features\/([^/]+)\//.exec(spec);
  if (aliasMatch && aliasMatch[1] !== importerFeature) return true;

  if (/^\.\.\//.test(spec) || /^\.\//.test(spec)) {
    // Resolve the relative spec against the importer's directory (relative to src).
    const importerDirParts = rel.split('/').slice(0, -1); // drop filename
    const resolved: string[] = [];
    const specParts = spec.split('/');
    let climbed = false;
    for (const seg of specParts) {
      if (seg === '..') {
        climbed = true;
        importerDirParts.pop();
      } else if (seg === '.' || seg === '') {
        continue;
      } else {
        resolved.push(seg);
      }
    }
    // After resolution, importerDirParts is the directory relative to src.
    // If the spec climbed above `features/` (into src root) and landed in
    // shared/entities/services/app — that is allowed. Only flag when the
    // resolved target stays inside features/ but in a different feature.
    if (climbed) {
      const finalDir = importerDirParts.join('/');
      if (finalDir === 'features') {
        // Climbed exactly to features/ root; the next resolved segment is the
        // sibling feature folder, e.g. ../../knowledge/... from features/auth/view-model.
        const targetFeature = resolved[0];
        if (
          targetFeature &&
          !NON_FEATURE_TOP_DIRS.has(targetFeature) &&
          targetFeature !== importerFeature
        ) {
          return true;
        }
      } else if (finalDir.startsWith('features/')) {
        const targetFeature = finalDir.split('/')[1];
        if (
          targetFeature &&
          !NON_FEATURE_TOP_DIRS.has(targetFeature) &&
          targetFeature !== importerFeature
        ) {
          return true;
        }
      }
      // climbed above features/ into src root and resolved lands in a sibling feature
      if (resolved.length > 0 && resolved[0] === 'features') {
        const targetFeature = resolved[1];
        if (
          targetFeature &&
          !NON_FEATURE_TOP_DIRS.has(targetFeature) &&
          targetFeature !== importerFeature
        ) {
          return true;
        }
      }
    }
  }
  return false;
};

describe('MVVM import boundaries (source scan)', () => {
  it('View/page/feature components do not import from services/http', () => {
    const files = listFiles(join(SRC, 'pages'), ['.ts', '.tsx']).concat(
      listFiles(join(SRC, 'features'), ['.ts', '.tsx']),
    );
    expect(files.length, 'no source files found — feature not yet scaffolded').toBeGreaterThan(0);

    const violations: string[] = [];
    for (const file of files) {
      if (!isViewLayer(file)) continue;
      for (const spec of readImports(file)) {
        if (isHttpTransport(spec)) {
          violations.push(`${relative(root, file)} -> ${spec}`);
        }
      }
    }
    expect(violations, `View imports transport:\n${violations.join('\n')}`).toEqual([]);
  });

  it('features do not import another feature internals', () => {
    const files = listFiles(join(SRC, 'features'), ['.ts', '.tsx']);
    if (files.length === 0) {
      // 22.1 does not create feature internals yet; the guard activates once
      // any feature source exists (22.2+). The next test proves the detector
      // actually fires on a violation, so skipping here is not weakening it.
      return;
    }

    const violations: string[] = [];
    for (const file of files) {
      for (const spec of readImports(file)) {
        if (isCrossFeatureInternal(file, spec)) {
          violations.push(`${relative(root, file)} -> ${spec}`);
        }
      }
    }
    expect(violations, `cross-feature imports:\n${violations.join('\n')}`).toEqual([]);
  });

  it('cross-feature detector fires on a synthetic violation (guard is real)', () => {
    // Synthetic importer located at src/features/auth/view-model/login.ts.
    const importer = join(SRC, 'features', 'auth', 'view-model', 'login.ts');
    expect(isCrossFeatureInternal(importer, '@features/knowledge/model/mapper')).toBe(true);
    expect(isCrossFeatureInternal(importer, '../../knowledge/model/mapper')).toBe(true);
    // Same-feature and shared imports are allowed.
    expect(isCrossFeatureInternal(importer, '@features/auth/api/auth')).toBe(false);
    expect(isCrossFeatureInternal(importer, '../../shared/ui/loading')).toBe(false);
    expect(isCrossFeatureInternal(importer, '@services/http/console-client')).toBe(false);
  });

  it('http-transport detector fires on a synthetic violation (guard is real)', () => {
    expect(isHttpTransport('@services/http/transport')).toBe(true);
    expect(isHttpTransport('../../services/http/console-client')).toBe(true);
    expect(isHttpTransport('@services/contracts/dto')).toBe(false);
    expect(isHttpTransport('react')).toBe(false);
  });

  it('AGENTS.md and CLAUDE.md are byte-identical', () => {
    const agents = readFileSync(join(root, 'AGENTS.md'), 'utf8');
    const claude = readFileSync(join(root, 'CLAUDE.md'), 'utf8');
    expect(claude, 'web/AGENTS.md and web/CLAUDE.md must be byte-identical').toBe(agents);
  });
});
