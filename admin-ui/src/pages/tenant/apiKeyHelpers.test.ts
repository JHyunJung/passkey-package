import { describe, it, expect } from 'vitest';
import { rotationOverdueCount, expiryState, daysLeft, latestCreatedAt, keyStatusCounts, previewExpiry, expiryBadge, ROTATION_DAYS } from './apiKeyHelpers';

const NOW = Date.parse('2026-10-11T00:00:00Z');
const DAY = 86_400_000;
const at = (days: number) => new Date(NOW + days * DAY).toISOString();

describe('rotationOverdueCount', () => {
  it('ACTIVE 이면서 생성 후 90일을 넘긴 키만 센다', () => {
    const keys = [
      { status: 'ACTIVE' as const, createdAt: at(-91) },   // 초과
      { status: 'ACTIVE' as const, createdAt: at(-89) },   // 아직
      { status: 'ACTIVE' as const, createdAt: at(-90) },   // 정확히 90일 — 초과 아님
      { status: 'REVOKED' as const, createdAt: at(-200) }, // 회수 키 제외
      { status: 'EXPIRED' as const, createdAt: at(-200) }, // 만료 키 제외
      { status: 'ACTIVE' as const, createdAt: 'garbage' }, // 파싱 실패 제외
    ];
    expect(ROTATION_DAYS).toBe(90);
    expect(rotationOverdueCount(keys, NOW)).toBe(1);
  });
  it('빈 목록은 0', () => {
    expect(rotationOverdueCount([], NOW)).toBe(0);
  });
});

describe('expiryState / daysLeft', () => {
  it('없음·만료·30일 이내·그 외', () => {
    expect(expiryState(null, NOW)).toBe('none');
    expect(expiryState(at(-1), NOW)).toBe('expired');
    expect(expiryState(at(10), NOW)).toBe('soon');
    expect(expiryState(at(30), NOW)).toBe('soon');
    expect(expiryState(at(31), NOW)).toBe('ok');
  });
  it('daysLeft 는 올림', () => {
    expect(daysLeft(at(9.2), NOW)).toBe(10);
  });
});

describe('기타 집계', () => {
  it('latestCreatedAt / keyStatusCounts', () => {
    const keys = [
      { status: 'ACTIVE' as const, createdAt: at(-3) },
      { status: 'REVOKED' as const, createdAt: at(-1) },
      { status: 'EXPIRED' as const, createdAt: at(-9) },
    ];
    expect(latestCreatedAt(keys)).toBe(at(-1));
    expect(latestCreatedAt([])).toBeNull();
    expect(keyStatusCounts(keys)).toEqual({ active: 1, expired: 1, revoked: 1 });
  });
  it('previewExpiry: 말일 보정(1/31 + 1개월 → 2/28), 무기한은 null', () => {
    // 기대값도 같은 KST 변환을 거쳐 만들어 실행 머신의 시간대와 무관하게 한다.
    const feb28 = new Date(2026, 1, 28, 12).toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
    expect(previewExpiry(1, new Date(2026, 0, 31, 12))).toBe(feb28);
    expect(previewExpiry(null)).toBeNull();
  });
});

describe('expiryBadge', () => {
  const k = (status: 'ACTIVE' | 'EXPIRED' | 'REVOKED', expiresAt: string | null) => ({ status, expiresAt });
  it('무기한 · 정상 · 만료 임박 · 만료됨 · 회수', () => {
    expect(expiryBadge(k('ACTIVE', null), NOW)).toEqual({ tone: 'neutral', label: '무기한', sub: '만료일 없음' });
    expect(expiryBadge(k('ACTIVE', at(100)), NOW)).toMatchObject({ tone: 'ok', label: '정상' });
    expect(expiryBadge(k('ACTIVE', at(10)), NOW)).toMatchObject({ tone: 'warn', label: '만료 임박 · 10일 남음' });
    expect(expiryBadge(k('EXPIRED', at(-1)), NOW)).toMatchObject({ tone: 'bad', label: '만료됨' });
    expect(expiryBadge(k('ACTIVE', at(-1)), NOW)).toMatchObject({ tone: 'bad', label: '만료됨' });
    expect(expiryBadge(k('REVOKED', at(10)), NOW)).toMatchObject({ tone: 'neutral', label: '해당 없음' });
  });
});
