import { describe, it, expect } from 'vitest';
import { ymd, ymdHm, hms, ymdHms, timeAgo, fmtNum } from './tabFormat';

describe('tabFormat (KST 고정)', () => {
  it('UTC 를 KST 로 바꿔 날짜·시각을 만든다', () => {
    expect(ymd('2026-06-07T15:30:05Z')).toBe('2026-06-08');
    expect(ymdHm('2026-06-07T15:30:05Z')).toBe('2026-06-08 00:30');
    expect(hms('2026-06-07T15:30:05Z')).toBe('00:30:05');
    expect(ymdHms('2026-06-07T15:30:05Z')).toBe('2026-06-08 00:30:05');
  });
  it('빈 값은 —, 파싱 실패는 원문', () => {
    expect(ymd(null)).toBe('—');
    expect(ymdHm(undefined)).toBe('—');
    expect(ymd('not-a-date')).toBe('not-a-date');
  });
  it('timeAgo', () => {
    const now = Date.parse('2026-10-11T00:00:00Z');
    expect(timeAgo('2026-10-10T23:59:30Z', now)).toBe('방금 전');
    expect(timeAgo('2026-10-10T23:15:00Z', now)).toBe('45분 전');
    expect(timeAgo('2026-10-10T21:00:00Z', now)).toBe('3시간 전');
    expect(timeAgo('2026-10-01T00:00:00Z', now)).toBe('10일 전');
    expect(timeAgo('2026-07-01T00:00:00Z', now)).toBe('3개월 전');
    expect(timeAgo(null, now)).toBe('—');
  });
  it('fmtNum 은 천 단위 구분', () => {
    expect(fmtNum(1234567)).toBe('1,234,567');
  });
});
