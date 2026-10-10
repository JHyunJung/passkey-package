import type { AuditEvent } from '@/api/designTypes';
import type { BadgeTone } from '@/ui';
import { dayLabel as kstDayLabel } from '@/lib/kst'; // Task 17 공용 구현

export type WindowKey = '24h' | '7d' | '30d';
export const WINDOWS: Record<WindowKey, { hours: number; label: string; long: string }> = {
  '24h': { hours: 24, label: '24시간', long: '최근 24시간' },
  '7d': { hours: 168, label: '7일', long: '최근 7일' },
  '30d': { hours: 720, label: '30일', long: '최근 30일' },
};

export function windowRange(hours: number, now = Date.now()): { from: string; to: string } {
  return { from: new Date(now - hours * 3_600_000).toISOString(), to: new Date(now).toISOString() };
}

// pages/ActivityPage.tsx 의 FAILURE_TYPES·이름 규칙과 같은 기준(앱 전체 실패 판정 일치).
export const FAILURE_TYPES = new Set(['SIGNATURE_COUNTER_REGRESSION', 'ATTESTATION_TRUST_FAILED', 'ADMIN_LOGIN_FAILED']);
const FAILURE_NAME = /_(FAILED|FAIL|REGRESSION)$/;

/** §6 ③ result 가 있으면 그 값, 없으면 액션 이름 기준. */
export function isFailure(e: Pick<AuditEvent, 'eventType' | 'result'>): boolean {
  if (e.result !== undefined) return e.result === 'FAILURE';
  return FAILURE_TYPES.has(e.eventType) || FAILURE_NAME.test(e.eventType);
}

export function eventTone(e: Pick<AuditEvent, 'eventType' | 'result'>): BadgeTone {
  if (isFailure(e)) return 'bad';
  if (/_(UPDATED|ROTATED|REVOKED|ISSUED|SUSPENDED|DELETED)$/.test(e.eventType)) return 'warn';
  if (/(SUCCESS|AUTHENTICATED|REGISTERED|LOGIN)$/.test(e.eventType)) return 'info';
  return 'neutral';
}

export type DiffEntry = { field: string; from: unknown; to: unknown };
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

export function payloadDiff(payload: Record<string, unknown> | null): DiffEntry[] {
  if (!payload) return [];
  const { before, after } = payload;
  if (isObj(before) && isObj(after)) {
    const keys = Array.from(new Set([...Object.keys(before), ...Object.keys(after)]));
    return keys
      .filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]))
      .map((k) => ({ field: k, from: before[k], to: after[k] }));
  }
  return Object.entries(payload)
    .filter(([, v]) => isObj(v) && 'from' in v && 'to' in v)
    .map(([k, v]) => ({ field: k, from: (v as Record<string, unknown>).from, to: (v as Record<string, unknown>).to }));
}

export function diffValue(v: unknown): string {
  if (v === undefined) return '—';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

/** 감사 로그 그룹 라벨 — Activity(Task 17)와 같은 규칙을 쓰도록 공용 구현에 위임한다. */
export function dayLabel(iso: string, now = Date.now()): string {
  return kstDayLabel(iso, new Date(now));
}
