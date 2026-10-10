// admin-ui/src/pages/tenant/tenantSignals.ts
// Tenant 상세 헤더의 탭 점 배지·개요 KPI 가 쓰는 순수 계산 함수.
import type { AaguidPolicy, ApiKey } from '@/api/designTypes';
import type { ChainOverview } from '@/api/auditChainMonitor';
import { EXPIRY_WARN_DAYS } from '@/lib/apiKeyExpiry'; // Task 16 에서 생성

export { EXPIRY_WARN_DAYS };
export type ChainRun = { at: string; ok: boolean };

const DAY_MS = 86_400_000;

/** 목록(mode≠ANY)에 보이는 항목 중 MDS 에서 모델을 찾지 못한(mdsName == null) AAGUID 수. */
export function unverifiedAaguidCount(policy: AaguidPolicy | null | undefined): number {
  if (!policy || policy.mode === 'ANY') return 0;
  return policy.entries.filter((e) => e.aaguid && e.mdsName == null).length;
}

export function hasUnverifiedAaguid(policy: AaguidPolicy | null | undefined): boolean {
  return unverifiedAaguidCount(policy) > 0;
}

/** ACTIVE 이고 지금부터 days 일 이내(경계 포함)에 만료되는 키 수. */
export function countExpiringKeys(keys: ApiKey[], now: number = Date.now(), days: number = EXPIRY_WARN_DAYS): number {
  const limit = now + days * DAY_MS;
  return keys.filter((k) => {
    if (k.status !== 'ACTIVE' || !k.expiresAt) return false;
    const t = new Date(k.expiresAt).getTime();
    return Number.isFinite(t) && t > now && t <= limit;
  }).length;
}

/** ChainOverview 에서 이 tenant 의 최근 검증 이력. 서버가 필드를 주지 않으면 undefined. */
export function pickRecentRuns(o: ChainOverview, tenantId: string): ChainRun[] | undefined {
  return o.tenants.find((t) => t.tenantId === tenantId)?.recentRuns;
}

export function recentRunsLabel(runs: ChainRun[]): string {
  const bad = runs.filter((r) => !r.ok).length;
  return bad === 0 ? `최근 ${runs.length}회 검증 모두 정상` : `최근 ${runs.length}회 검증 중 ${bad}회 위변조`;
}
