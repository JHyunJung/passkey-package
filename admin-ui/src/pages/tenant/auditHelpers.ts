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

// ── 이벤트 종류 그룹 (V3 감사 로그) — 서버에 그룹 파라미터가 없어 프론트에서 매핑한다(스펙 §8) ──

export type EventGroup = 'auth' | 'registration' | 'config' | 'keys' | 'other';

export const EVENT_GROUPS: { id: EventGroup; label: string; codes: string }[] = [
  { id: 'auth', label: '인증', codes: 'AUTH_* · AUTHENTICATION_* · CREDENTIAL_AUTHENTICATED · SIGNATURE_*' },
  { id: 'registration', label: '등록', codes: 'REGISTRATION_* · CREDENTIAL_REGISTERED · ATTESTATION_TRUST_*' },
  { id: 'config', label: '설정 변경', codes: 'WEBAUTHN_CONFIG_* · AAGUID_* · ATTESTATION_POLICY_* · TENANT_*' },
  { id: 'keys', label: '키 관리', codes: 'API_KEY_* · CREDENTIAL_REVOKE*' },
  { id: 'other', label: '기타', codes: '위 네 그룹에 속하지 않는 이벤트' },
];

/** 이벤트 타입 → 그룹. 어느 그룹에도 맞지 않는 이벤트는 'other' — 필터를 켜도 숨겨지지 않게 한다. */
export function eventGroup(type: string): EventGroup {
  if (/^AUTH(ENTICATION)?_/.test(type) || type === 'CREDENTIAL_AUTHENTICATED' || /^SIGNATURE_/.test(type)) return 'auth';
  if (/^REGISTRATION_/.test(type) || type === 'CREDENTIAL_REGISTERED' || /^ATTESTATION_TRUST_/.test(type)) return 'registration';
  if (/^(WEBAUTHN_CONFIG|AAGUID|ATTESTATION_POLICY|TENANT)_/.test(type)) return 'config';
  if (/^API_KEY_/.test(type) || /^CREDENTIAL_REVOKE/.test(type)) return 'keys';
  return 'other';
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
