import { describe, it, expect } from 'vitest';
import { buildDiffRows, arrayChange } from './activityDiff';

const tenantUpdate = JSON.stringify({
  before: { displayName: 'Acme', webauthnTimeoutMs: 60000, allowedOrigins: ['https://acme.com'] },
  after: { displayName: 'Acme', webauthnTimeoutMs: 120000, allowedOrigins: ['https://acme.com', 'https://b.acme.com'] },
  changedFields: ['allowedOrigins', 'webauthnTimeoutMs'],
});

describe('buildDiffRows', () => {
  it('diff 필드가 있으면 그대로 쓴다(서버 ③)', () => {
    const diff = [{ field: 'rpName', from: 'A', to: 'B' }];
    expect(buildDiffRows({ diff, payload: tenantUpdate })).toEqual(diff);
  });
  it('diff 가 없으면 TENANT_UPDATE before/after 를 changedFields 순서로', () => {
    expect(buildDiffRows({ payload: tenantUpdate })).toEqual([
      { field: 'allowedOrigins', from: ['https://acme.com'], to: ['https://acme.com', 'https://b.acme.com'] },
      { field: 'webauthnTimeoutMs', from: 60000, to: 120000 },
    ]);
  });
  it('changedFields 가 없으면 값이 다른 키만', () => {
    const payload = JSON.stringify({ before: { a: 1, b: 2 }, after: { a: 1, b: 3 } });
    expect(buildDiffRows({ payload })).toEqual([{ field: 'b', from: 2, to: 3 }]);
  });
  it('before/after 가 없거나 JSON 이 아니면 빈 배열', () => {
    expect(buildDiffRows({ payload: JSON.stringify({ ip: '1.2.3.4' }) })).toEqual([]);
    expect(buildDiffRows({ payload: 'not json' })).toEqual([]);
  });
});

describe('arrayChange', () => {
  it('추가/제거 원소', () => {
    expect(arrayChange(['a', 'b'], ['b', 'c'])).toEqual({ added: ['c'], removed: ['a'] });
  });
  it('배열이 아니면 null', () => {
    expect(arrayChange(1, 2)).toBeNull();
  });
});
