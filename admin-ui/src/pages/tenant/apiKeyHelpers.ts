// admin-ui/src/pages/tenant/apiKeyHelpers.ts
import type { ApiKey } from '@/api/designTypes';
import type { BadgeTone } from '@/ui';
import { EXPIRY_WARN_DAYS } from '@/lib/apiKeyExpiry'; // Task 16 에서 생성

import { ymd } from './tabFormat';

export { EXPIRY_WARN_DAYS };
const DAY = 86_400_000;
export const ROTATION_DAYS = 90;
export type ExpiryState = 'none' | 'expired' | 'soon' | 'ok';

export function expiryState(expiresAt: string | null, now = Date.now()): ExpiryState {
  if (!expiresAt) return 'none';
  const exp = new Date(expiresAt).getTime();
  if (exp <= now) return 'expired';
  if (exp - now <= EXPIRY_WARN_DAYS * DAY) return 'soon';
  return 'ok';
}

export function daysLeft(expiresAt: string, now = Date.now()): number {
  return Math.ceil((new Date(expiresAt).getTime() - now) / DAY);
}

/** 권장 rotation(기본 90일)을 넘긴 ACTIVE 키 수. createdAt 기준. */
export function rotationOverdueCount(keys: Pick<ApiKey, 'status' | 'createdAt'>[], now = Date.now(), days = ROTATION_DAYS): number {
  return keys.filter((k) => {
    if (k.status !== 'ACTIVE') return false;
    const t = new Date(k.createdAt).getTime();
    return !isNaN(t) && now - t > days * DAY;
  }).length;
}

export function latestCreatedAt(keys: Pick<ApiKey, 'createdAt'>[]): string | null {
  let best: string | null = null;
  for (const k of keys) {
    if (best === null || new Date(k.createdAt).getTime() > new Date(best).getTime()) best = k.createdAt;
  }
  return best;
}

export function keyStatusCounts(keys: Pick<ApiKey, 'status'>[]) {
  return {
    active: keys.filter((k) => k.status === 'ACTIVE').length,
    expired: keys.filter((k) => k.status === 'EXPIRED').length,
    revoked: keys.filter((k) => k.status === 'REVOKED').length,
  };
}

// now + N개월의 날짜 미리보기(YYYY-MM-DD, Asia/Seoul). null(무기한)이면 null.
// 말일 보정: 1/31 + 1개월처럼 다음 달에 같은 일자가 없으면 그 달 말일로 클램프.
export function previewExpiry(months: number | null, now = new Date()): string | null {
  if (months == null) return null;
  const lastDayOfTargetMonth = new Date(now.getFullYear(), now.getMonth() + months + 1, 0).getDate();
  const day = Math.min(now.getDate(), lastDayOfTargetMonth);
  const target = new Date(now.getFullYear(), now.getMonth() + months, day, now.getHours(), now.getMinutes(), now.getSeconds());
  return target.toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
}

/** 만료 열 배지 — 색만이 아니라 글로 상태를 말한다(정상 · 만료 임박 N일 · 만료됨 · 무기한). */
export function expiryBadge(k: Pick<ApiKey, 'status' | 'expiresAt'>, now = Date.now()): { tone: BadgeTone; label: string; sub: string } {
  if (!k.expiresAt) return { tone: 'neutral', label: '무기한', sub: '만료일 없음' };
  const sub = ymd(k.expiresAt);
  if (k.status === 'REVOKED') return { tone: 'neutral', label: '해당 없음', sub };
  const st = k.status === 'EXPIRED' ? 'expired' : expiryState(k.expiresAt, now);
  if (st === 'expired') return { tone: 'bad', label: '만료됨', sub };
  if (st === 'soon') return { tone: 'warn', label: `만료 임박 · ${daysLeft(k.expiresAt, now)}일 남음`, sub };
  return { tone: 'ok', label: '정상', sub };
}
