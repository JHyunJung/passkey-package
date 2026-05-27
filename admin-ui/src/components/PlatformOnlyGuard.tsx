import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMe } from '../me/MeContext';

export default function PlatformOnlyGuard() {
    const { me } = useMe();
    const nav = useNavigate();
    useEffect(() => {
        if (me?.role === 'RP_ADMIN' && me.tenantId) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
        }
    }, [me, nav]);
    return null;
}
