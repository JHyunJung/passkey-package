import { describe, it, expect } from 'vitest';
import { isExpiringSoon } from './apiKeyExpiry';

const now = new Date('2026-10-11T00:00:00Z');

describe('isExpiringSoon (30일)', () => {
  it('30일 이내 만료 예정 ACTIVE 키는 true', () => {
    expect(isExpiringSoon({ expiresAt: '2026-10-21T00:00:00Z' }, now)).toBe(true);
    expect(isExpiringSoon({ expiresAt: '2026-11-10T00:00:00Z' }, now)).toBe(true); // 정확히 30일
  });
  it('30일 초과는 false', () => {
    expect(isExpiringSoon({ expiresAt: '2026-11-10T00:00:01Z' }, now)).toBe(false);
  });
  it('이미 만료·회수·무기한은 false', () => {
    expect(isExpiringSoon({ expiresAt: '2026-10-10T00:00:00Z' }, now)).toBe(false);
    expect(isExpiringSoon({ expiresAt: '2026-10-21T00:00:00Z', revokedAt: '2026-10-01T00:00:00Z' }, now)).toBe(false);
    expect(isExpiringSoon({}, now)).toBe(false);
    expect(isExpiringSoon({ expiresAt: null }, now)).toBe(false);
  });
});
