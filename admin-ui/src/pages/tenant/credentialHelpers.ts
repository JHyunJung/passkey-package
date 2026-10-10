// admin-ui/src/pages/tenant/credentialHelpers.ts
import type { Credential } from '@/api/designTypes';

export type StatusFilter = 'ALL' | 'ACTIVE' | 'REVOKED';
export type ClientFilter = { aaguid: string; status: StatusFilter };

const norm = (s: string) => s.replace(/-/g, '').toLowerCase();

/** 서버에 aaguid·status 파라미터가 없어 현재 로드된 페이지 안에서만 거른다. */
export function hasClientFilter(f: ClientFilter): boolean {
  return f.aaguid.trim() !== '' || f.status !== 'ALL';
}

export function applyClientFilter(items: Credential[], f: ClientFilter): Credential[] {
  const a = norm(f.aaguid.trim());
  return items.filter((c) =>
    (f.status === 'ALL' || c.status === f.status) &&
    (a === '' || (c.aaguid != null && norm(c.aaguid).includes(a))));
}

export function authenticatorLabel(c: Credential): string {
  if (c.authenticatorName) return c.authenticatorName;
  return c.aaguid ? norm(c.aaguid).slice(-8) : 'unknown';
}
