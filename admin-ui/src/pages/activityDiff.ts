// admin-ui/src/pages/activityDiff.ts
import type { ActivityDetailView } from '@/api/types';

export type DiffRow = { field: string; from: unknown; to: unknown };

function asObject(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
}

/**
 * 상세 패널 "어떻게 바뀜" 행.
 * 1) 서버 ③ 의 detail.diff 가 정의되어 있으면 그대로.
 * 2) 아니면 payload 의 before/after 객체(현재 TENANT_UPDATE: { before, after, changedFields }).
 *    changedFields 가 문자열 배열이면 그 순서, 아니면 JSON 값이 다른 키만.
 */
export function buildDiffRows(detail: Pick<ActivityDetailView, 'diff' | 'payload'>): DiffRow[] {
  if (detail.diff !== undefined) return detail.diff;
  let parsed: unknown;
  try { parsed = JSON.parse(detail.payload); } catch { return []; }
  const p = asObject(parsed);
  if (!p) return [];
  const before = asObject(p.before);
  const after = asObject(p.after);
  if (!before || !after) return [];
  const fields = Array.isArray(p.changedFields) && p.changedFields.every((f) => typeof f === 'string')
    ? (p.changedFields as string[])
    : Object.keys({ ...before, ...after }).filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]));
  return fields.map((field) => ({ field, from: before[field], to: after[field] }));
}

/** 두 값이 모두 배열이면 추가/제거 원소(문자열화), 아니면 null. */
export function arrayChange(from: unknown, to: unknown): { added: string[]; removed: string[] } | null {
  if (!Array.isArray(from) || !Array.isArray(to)) return null;
  const f = from.map(String);
  const t = to.map(String);
  return { added: t.filter((x) => !f.includes(x)), removed: f.filter((x) => !t.includes(x)) };
}
