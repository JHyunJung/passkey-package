import { useEffect, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMe } from '../me/MeContext';

/**
 * 페이지를 PlatformOnlyGuard 로 감싸면 — RP_ADMIN 인 경우 자기 tenant detail
 * 로 redirect 하고 children 은 안 렌더 (RP_ADMIN 이 잠깐도 보지 못함).
 * loading 동안에는 children 렌더 보류.
 */
interface Props {
    children: ReactNode;
}

export default function PlatformOnlyGuard({ children }: Props) {
    const { me, loading } = useMe();
    const nav = useNavigate();

    useEffect(() => {
        if (!loading && me?.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
        }
    }, [me, loading, nav]);

    // loading 또는 RP_ADMIN 이면 children 안 렌더
    if (loading) return <div className="muted" style={{ padding: 24 }}>불러오는 중…</div>;
    if (me?.role === 'RP_ADMIN') return null;
    return <>{children}</>;
}
