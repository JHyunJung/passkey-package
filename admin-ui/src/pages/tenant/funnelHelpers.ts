// admin-ui/src/pages/tenant/funnelHelpers.ts
import type { FunnelData, FunnelSeries } from '@/api/funnel';

export type FunnelStep = { label: string; value: number; pctOfFirst: number | null; convFromPrev: number | null; dropFromPrev: number };
export type FunnelSummary = {
  steps: FunnelStep[];
  overallRate: number | null; // 인증 성공 / 등록 시도
  biggestDrop: { from: string; to: string; lost: number; rate: number } | null;
};

const LABELS = ['등록 시도', '등록 성공', '인증 시도', '인증 성공'] as const;

export function funnelSummary(f: FunnelData): FunnelSummary {
  const values = [f.registration.attempts, f.registration.success, f.authentication.attempts, f.authentication.success];
  const first = values[0];
  const steps: FunnelStep[] = values.map((v, i) => {
    const prev = i > 0 ? values[i - 1] : null;
    return {
      label: LABELS[i],
      value: v,
      pctOfFirst: first > 0 ? v / first : null,
      convFromPrev: prev != null && prev > 0 ? v / prev : null,
      dropFromPrev: prev != null ? Math.max(0, prev - v) : 0,
    };
  });
  let biggestDrop: FunnelSummary['biggestDrop'] = null;
  for (let i = 1; i < steps.length; i++) {
    const lost = steps[i].dropFromPrev;
    if (lost > 0 && (biggestDrop === null || lost > biggestDrop.lost)) {
      biggestDrop = { from: steps[i - 1].label, to: steps[i].label, lost, rate: lost / values[i - 1] };
    }
  }
  return { steps, overallRate: first > 0 ? values[3] / first : null, biggestDrop };
}

function successRate(s: FunnelSeries[]): number | null {
  const attempts = s.reduce((a, d) => a + d.attempts, 0);
  return attempts > 0 ? s.reduce((a, d) => a + d.success, 0) / attempts : null;
}

/** series(오래된 날 → 오늘)의 마지막 windowDays 일 vs 그 앞 windowDays 일 인증 성공률 차이(%p). */
export function priorPeriodDelta(series: FunnelSeries[], windowDays: number): number | null {
  if (windowDays <= 0 || series.length < windowDays * 2) return null;
  const cur = successRate(series.slice(-windowDays));
  const prior = successRate(series.slice(-windowDays * 2, -windowDays));
  if (cur === null || prior === null) return null;
  return (cur - prior) * 100;
}

export function fmtPct(r: number | null, digits = 1): string {
  return r === null ? '—' : `${(r * 100).toFixed(digits)}%`;
}

export function fmtDeltaPp(d: number): string {
  const v = Math.abs(d).toFixed(1);
  if (v === '0.0') return '±0.0%p';
  return `${d > 0 ? '+' : '-'}${v}%p`;
}
