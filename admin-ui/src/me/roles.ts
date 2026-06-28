import type { Me } from '@/api/types';

/** PLATFORM_OPERATOR(플랫폼 운영자, 전체 접근). */
export function isPlatform(me: Me): boolean {
  return me.role === 'PLATFORM_OPERATOR';
}

/** RP_ADMIN(고객사 관리자, 자기 테넌트만). */
export function isRpAdmin(me: Me): boolean {
  return me.role === 'RP_ADMIN';
}

/** RP_ADMIN 의 첫 번째(활성) 테넌트 id. PLATFORM 이거나 tenantIds 비어있으면 null. */
export function rpTenantId(me: Me): string | null {
  return me.role === 'RP_ADMIN' && me.tenantIds.length > 0 ? me.tenantIds[0] : null;
}
