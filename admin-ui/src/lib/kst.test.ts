// admin-ui/src/lib/kst.test.ts
import { describe, it, expect } from 'vitest';
import { kstDate, kstTime, kstDateTime, utcIso } from './kst';

describe('kst formatters', () => {
  it('UTC 15:10 은 KST 다음날 00:10', () => {
    expect(kstDate('2026-10-11T15:10:00Z')).toBe('2026-10-12');
    expect(kstTime('2026-10-11T15:10:00Z')).toBe('00:10');
  });
  it('kstDateTime 은 초까지 24시간제', () => {
    expect(kstDateTime('2026-10-11T05:02:03Z')).toBe('2026-10-11 14:02:03');
  });
  it('+09:00 오프셋 입력도 같은 시각으로 해석', () => {
    expect(kstDateTime('2026-10-11T14:02:03+09:00')).toBe('2026-10-11 14:02:03');
  });
  it('utcIso 는 밀리초 없는 Z 표기', () => {
    expect(utcIso('2026-10-11T14:02:03+09:00')).toBe('2026-10-11T05:02:03Z');
  });
  it('파싱 불가 입력은 원문 반환', () => {
    expect(kstDate('not-a-date')).toBe('not-a-date');
    expect(utcIso('not-a-date')).toBe('not-a-date');
  });
});
