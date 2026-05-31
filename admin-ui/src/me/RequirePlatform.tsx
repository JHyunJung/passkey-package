import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import type { Me } from '@/api/types';
import { isPlatform, rpTenantId } from './roles';

/**
 * PLATFORM 전용 route guard. PLATFORM_OPERATOR 면 children, RP_ADMIN 이면
 * 자기 테넌트(/tenants/{tenantId})로 redirect. RP_ADMIN 인데 tenantId 가
 * 없으면(데이터 이상) 에러 상태(redirect loop 방지). 보안 경계는 BE — 이 가드는 IA/UX.
 */
export function RequirePlatform({ me, children }: { me: Me; children: ReactNode }) {
  if (isPlatform(me)) {
    return <>{children}</>;
  }
  const tid = rpTenantId(me);
  if (tid) {
    return <Navigate to={`/tenants/${tid}`} replace />;
  }
  return (
    <div className="page" style={{ padding: 24 }}>
      <h1 className="page__title">계정 구성 오류</h1>
      <div className="page__sub">RP_ADMIN 계정에 테넌트가 지정돼 있지 않습니다. 플랫폼 운영자에게 문의하세요.</div>
    </div>
  );
}
