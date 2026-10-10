import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
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
});
