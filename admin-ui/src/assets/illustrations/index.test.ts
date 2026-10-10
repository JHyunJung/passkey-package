import { describe, it, expect } from 'vitest';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { illustrations } from './index';

const FILES = ['login-hero', 'empty-tenants', 'empty-credentials', 'all-clear', 'mfa', 'recovery-codes', 'no-results', 'api-key-issued', 'signup-sent', 'load-error'];

describe('illustrations', () => {
  it('10개 webp 파일이 존재한다', () => {
    for (const f of FILES) expect(existsSync(join(__dirname, `${f}.webp`))).toBe(true);
  });
  it('맵에 10개 키가 모두 문자열로 있다', () => {
    expect(Object.keys(illustrations)).toHaveLength(10);
    for (const v of Object.values(illustrations)) expect(typeof v).toBe('string');
  });
});
