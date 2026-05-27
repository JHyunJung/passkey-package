import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import OverviewTab from './tenant/OverviewTab';
import WebAuthnConfigTab from './tenant/WebAuthnConfigTab';
import CredentialsTab from './tenant/CredentialsTab';
import ApiKeysTab from './tenant/ApiKeysTab';
import TenantActivityTab from './tenant/TenantActivityTab';
import type { TenantView } from '../api/types';
import { useMe } from '../me/MeContext';

type TabKey = 'overview' | 'webauthn' | 'credentials' | 'apikeys' | 'activity';

const TABS: { key: TabKey; label: string }[] = [
    { key: 'overview',    label: 'Overview' },
    { key: 'webauthn',    label: 'WebAuthn Configuration' },
    { key: 'credentials', label: 'Credentials' },
    { key: 'apikeys',     label: 'API Keys' },
    { key: 'activity',    label: 'Activity' },
];

export default function TenantDetail() {
    const { id } = useParams<{ id: string }>();
    const nav = useNavigate();
    const { me } = useMe();
    const [tenant, setTenant] = useState<TenantView | null>(null);
    const [tab, setTab] = useState<TabKey>('overview');
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!id) return;
        if (me?.role === 'RP_ADMIN' && me.tenantId && me.tenantId !== id) {
            nav(`/tenants/${me.tenantId}`, { replace: true });
            return;
        }
        setError(null);
        api.get<TenantView>(`/admin/api/tenants/${id}`)
            .then(setTenant)
            .catch((e) => setError(e?.message ?? 'tenant 조회 실패'));
    }, [id, me]);

    if (error) {
        return (
            <div className="stack-3">
                <button className="btn btn--ghost" onClick={() => nav('/tenants')}>← 목록으로</button>
                <div className="banner banner--danger">{error}</div>
            </div>
        );
    }

    if (!tenant) {
        return <div className="muted">불러오는 중…</div>;
    }

    return (
        <div className="stack-4">
            <div className="stack-2">
                <button className="btn btn--ghost btn--sm" onClick={() => nav('/tenants')}>
                    ← 목록으로
                </button>
                <div className="row" style={{ gap: 12, alignItems: 'baseline' }}>
                    <h1 style={{ margin: 0 }}>{tenant.displayName}</h1>
                    <span className="muted" style={{
                        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                    }}>{tenant.slug}</span>
                    <span className={'badge ' + (tenant.status === 'active' ? 'badge--success' : 'badge--warning')}>
                        {tenant.status}
                    </span>
                </div>
            </div>

            <div className="tabs">
                {TABS.map((t) => (
                    <button
                        key={t.key}
                        className={'tabs__btn ' + (tab === t.key ? 'tabs__btn--active' : '')}
                        onClick={() => setTab(t.key)}
                    >
                        {t.label}
                    </button>
                ))}
            </div>

            {tab === 'overview'    && <OverviewTab tenant={tenant} />}
            {tab === 'webauthn'    && <WebAuthnConfigTab tenant={tenant} onUpdated={setTenant} />}
            {tab === 'credentials' && <CredentialsTab tenantId={tenant.id} />}
            {tab === 'apikeys'     && <ApiKeysTab tenantId={tenant.id} />}
            {tab === 'activity'    && <TenantActivityTab tenantId={tenant.id} />}
        </div>
    );
}
