import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(__dirname, '..', '..');
const FORBIDDEN = /(fonts\.googleapis\.com|fonts\.gstatic\.com|cdn\.jsdelivr\.net)/;

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

describe('폐쇄망 대응: 외부 폰트/CDN 참조 금지', () => {
  it('index.html 에 외부 폰트 링크가 없다', () => {
    expect(readFileSync(join(ROOT, 'index.html'), 'utf8')).not.toMatch(FORBIDDEN);
  });
  it('src/ 의 ts/tsx/css 에 외부 폰트 URL 이 없다', () => {
    const offenders = walk(join(ROOT, 'src'))
      .filter((f) => /\.(tsx?|css)$/.test(f) && !f.endsWith('noExternalAssets.test.ts'))
      .filter((f) => FORBIDDEN.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });
});
