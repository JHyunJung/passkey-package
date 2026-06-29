import type { Me } from '@/api/types';

/** PLATFORM_OPERATOR(플랫폼 운영자, 전체 접근). */
export function isPlatform(me: Me): boolean {
  return me.role === 'PLATFORM_OPERATOR';
}

/** RP_ADMIN(고객사 관리자, 자기 테넌트만). */
export function isRpAdmin(me: Me): boolean {
  return me.role === 'RP_ADMIN';
}

/**
 * RP_ADMIN 의 기본 활성 테넌트 id. PLATFORM 이거나 tenantIds 비어있으면 null.
 * tenantIds 는 백엔드(MeController)가 UUID 자연 순서로 정렬해 내려주므로, [0] 은
 * ActiveTenantResolver 의 기본 활성 RP(TreeSet.first())와 동일한 RP 를 가리킨다.
 */
export function rpTenantId(me: Me): string | null {
  return me.role === 'RP_ADMIN' && me.tenantIds.length > 0 ? me.tenantIds[0] : null;
}
