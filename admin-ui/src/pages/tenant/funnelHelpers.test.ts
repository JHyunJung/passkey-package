// admin-ui/src/pages/tenant/funnelHelpers.test.ts
import { describe, it, expect } from 'vitest';
import type { FunnelData, FunnelSeries } from '@/api/funnel';
import { funnelSummary, priorPeriodDelta, fmtPct, fmtDeltaPp } from './funnelHelpers';

const data = (reg: [number, number], auth: [number, number]): FunnelData => ({
  windowDays: 7,
  registration: { attempts: reg[0], success: reg[1], ratio: 0 },
  authentication: { attempts: auth[0], success: auth[1], ratio: 0 },
  conversion: 0, series: [], byEventType: [],
});
const series = (n: number, success: (i: number) => number, attempts: (i: number) => number = () => 10): FunnelSeries[] =>
  Array.from({ length: n }, (_, i) => ({ day: `D${i}`, attempts: attempts(i), success: success(i) }));

describe('funnelSummary', () => {
  it('단계·전환율·이탈·최대 이탈 단계·전체 전환율', () => {
    const s = funnelSummary(data([100, 80], [70, 63]));
    expect(s.steps.map((x) => x.label)).toEqual(['등록 시도', '등록 성공', '인증 시도', '인증 성공']);
    expect(s.steps.map((x) => x.value)).toEqual([100, 80, 70, 63]);
    expect(s.steps.map((x) => x.pctOfFirst)).toEqual([1, 0.8, 0.7, 0.63]);
    expect(s.steps.map((x) => x.convFromPrev)).toEqual([null, 0.8, 0.875, 0.9]);
    expect(s.steps.map((x) => x.dropFromPrev)).toEqual([0, 20, 10, 7]);
    expect(s.overallRate).toBeCloseTo(0.63);
    expect(s.biggestDrop).toEqual({ from: '등록 시도', to: '등록 성공', lost: 20, rate: 0.2 });
  });
  it('단계가 늘어나는 구간은 이탈 0, 감소가 없으면 biggestDrop=null', () => {
    const s = funnelSummary(data([10, 10], [40, 40]));
    expect(s.steps.map((x) => x.dropFromPrev)).toEqual([0, 0, 0, 0]);
    expect(s.biggestDrop).toBeNull();
    expect(s.overallRate).toBe(4);
  });
  it('모두 0 이면 비율은 null (0/0 금지)', () => {
    const s = funnelSummary(data([0, 0], [0, 0]));
    expect(s.overallRate).toBeNull();
    expect(s.steps.every((x) => x.pctOfFirst === null)).toBe(true);
    expect(s.steps[1].convFromPrev).toBeNull();
    expect(s.biggestDrop).toBeNull();
  });
});

describe('priorPeriodDelta', () => {
  it('마지막 N일 vs 그 앞 N일 성공률 차이(%p)', () => {
    // 30일: 앞쪽 23일 성공 5/10, 마지막 7일 성공 8/10 → 직전 7일(인덱스 16..22) 50% vs 최근 7일 80%
    const s = series(30, (i) => (i >= 23 ? 8 : 5));
    expect(priorPeriodDelta(s, 7)).toBeCloseTo(30);
  });
  it('하락은 음수', () => {
    const s = series(14, (i) => (i >= 7 ? 4 : 6));
    expect(priorPeriodDelta(s, 7)).toBeCloseTo(-20);
  });
  it('데이터 부족·시도 0건이면 null', () => {
    expect(priorPeriodDelta(series(10, () => 5), 7)).toBeNull();
    expect(priorPeriodDelta(series(14, () => 0, (i) => (i < 7 ? 0 : 10)), 7)).toBeNull();
    expect(priorPeriodDelta([], 7)).toBeNull();
  });
});

describe('format', () => {
  it('fmtPct / fmtDeltaPp', () => {
    expect(fmtPct(0.63)).toBe('63.0%');
    expect(fmtPct(0.875, 0)).toBe('88%');
    expect(fmtPct(null)).toBe('—');
    expect(fmtDeltaPp(30.0000001)).toBe('+30.0%p');
    expect(fmtDeltaPp(-2.46)).toBe('-2.5%p');
    expect(fmtDeltaPp(0)).toBe('±0.0%p');
  });
});
