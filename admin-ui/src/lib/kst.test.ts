// admin-ui/src/lib/kst.test.ts
import { describe, it, expect } from 'vitest';
import { kstDate, kstTime, kstDateTime, utcIso, dayLabel, groupByDay } from './kst';

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

describe('dayLabel (KST 자정 경계)', () => {
  // now = KST 2026-10-12 00:30
  const now = new Date('2026-10-11T15:30:00Z');
  it('같은 KST 날짜는 오늘', () => {
    expect(dayLabel('2026-10-11T15:10:00Z', now)).toBe('오늘'); // KST 10-12 00:10
  });
  it('UTC 로는 같은 날이어도 KST 전날이면 어제', () => {
    expect(dayLabel('2026-10-11T14:50:00Z', now)).toBe('어제'); // KST 10-11 23:50
  });
  it('그 이전은 KST 날짜', () => {
    expect(dayLabel('2026-10-10T14:50:00Z', now)).toBe('2026-10-10'); // KST 10-10 23:50
  });
});

describe('groupByDay', () => {
  it('최신순 입력을 연속 구간으로 묶고 순서를 유지한다', () => {
    const now = new Date('2026-10-11T03:00:00Z'); // KST 10-11 12:00
    const items = [
      { id: 'a', ts: '2026-10-11T02:00:00Z' },
      { id: 'b', ts: '2026-10-11T00:00:00Z' },
      { id: 'c', ts: '2026-10-10T09:00:00Z' },
      { id: 'd', ts: '2026-10-08T01:00:00Z' },
    ];
    const g = groupByDay(items, (i) => i.ts, now);
    expect(g.map((x) => x.label)).toEqual(['오늘', '어제', '2026-10-08']);
    expect(g[0].items.map((i) => i.id)).toEqual(['a', 'b']);
  });
  it('빈 입력은 빈 배열', () => {
    expect(groupByDay([], (i: { ts: string }) => i.ts, new Date())).toEqual([]);
  });
});
