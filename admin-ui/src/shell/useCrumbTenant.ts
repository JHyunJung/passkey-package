import { useEffect, useState } from 'react';
import { tenantsApi } from '@/api/tenants';

export type CrumbTenant = { id: string; name: string };

/** 테넌트 경로의 브레드크럼(Tenants › {이름} › {탭})에 쓸 이름. 테넌트 경로가 아니거나 조회 실패면 null. */
export function useCrumbTenant(tenantId: string | null): CrumbTenant | null {
  const [tenant, setTenant] = useState<CrumbTenant | null>(null);
  useEffect(() => {
    setTenant(null);
    if (!tenantId) return;
    let cancelled = false;
    tenantsApi.get(tenantId)
      .then((t) => { if (!cancelled) setTenant({ id: t.id, name: t.name }); })
      .catch(() => { /* 이름 없이 Tenants 만 */ });
    return () => { cancelled = true; };
  }, [tenantId]);
  return tenant && tenant.id === tenantId ? tenant : null;
}
