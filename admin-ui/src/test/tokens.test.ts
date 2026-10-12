import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const S = join(__dirname, '..', 'styles');
const tokens = () => readFileSync(join(S, 'tokens.css'), 'utf8');

describe('V2 tokens', () => {
  it('CI 브랜드 색을 정의한다', () => {
    expect(tokens()).toMatch(/--brand-700:\s*#0B4EA2/i);
    expect(tokens()).toMatch(/--brand-500:\s*#1E88D8/i);
    expect(tokens()).toMatch(/--ink:\s*#16243A/i);
  });
  it('다크/밀도/테이블/사이드바 분기가 없다', () => {
    const all = tokens() + readFileSync(join(S, 'components.css'), 'utf8');
    expect(all).not.toMatch(/data-theme|data-density|data-tablestyle|data-sidebar/);
  });
  it('tweaks 폴더가 삭제되었다', () => {
    expect(existsSync(join(__dirname, '..', 'tweaks'))).toBe(false);
  });
  it('IBM Plex 를 기본 폰트로 쓴다', () => {
    expect(tokens()).toMatch(/--font:\s*'IBM Plex Sans KR'/);
  });

  it('전환기간 별칭이 정의·참조되지 않는다', () => {
    const ALIASES = ['--accent', '--text', '--text-soft', '--text-mute', '--text-faint', '--border', '--border-subtle', '--border-strong', '--bg', '--surface-2', '--surface-3', '--surface-sunk', '--success', '--warning', '--danger', '--info', '--violet', '--teal', '--shadow-xs', '--shadow-sm', '--shadow-md', '--shadow-lg', '--radius'];
    const walk = (dir: string): string[] =>
      readdirSync(dir).flatMap((n) => { const p = join(dir, n); return statSync(p).isDirectory() ? walk(p) : [p]; });
    const src = join(__dirname, '..');
    const offenders: string[] = [];
    for (const f of walk(src).filter((f) => /\.(tsx?|css)$/.test(f) && !f.endsWith('tokens.test.ts'))) {
      const text = readFileSync(f, 'utf8');
      for (const a of ALIASES) {
        // 앞이 식별자 문자가 아닌 `--accent` / `--accent-soft` 뒤에 `:` 또는 `)` (선언·var() 참조). badge--danger 같은 클래스명은 제외.
        const re = new RegExp(`(?<![a-z0-9-])${a}(-[a-z0-9]+)?\\s*[:)]`);
        if (re.test(text)) offenders.push(`${f.replace(src, 'src')} ${a}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('포커스 링은 2px 실선 outline 이고 흐린 box-shadow 링을 쓰지 않는다', () => {
    const css = tokens() + readFileSync(join(S, 'components.css'), 'utf8');
    expect(tokens()).toMatch(/--focus-outline:\s*2px solid var\(--brand-500\)/);
    expect(tokens()).toMatch(/--focus-offset:\s*2px/);
    expect(css).not.toMatch(/--focus-ring/);
    expect(css).toMatch(/:focus-visible\s*\{[^}]*outline:\s*var\(--focus-outline\)/);
  });
});
