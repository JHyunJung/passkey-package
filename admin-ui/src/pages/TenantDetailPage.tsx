import { useState, useEffect } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { StatusBadge } from '@/shell/StatusBadge';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import type { Me } from '@/api/types';
import { useToast } from '@/shell/ToastHost';
import TenantOverview from '@/pages/tenant/TenantOverview';
import WebauthnConfigTab from '@/pages/tenant/WebauthnConfigTab';
import AaguidPolicyTab from '@/pages/tenant/AaguidPolicyTab';
import ApiKeysTab from '@/pages/tenant/ApiKeysTab';

// ── Local util (mirrors design pages-2.jsx global fmtDateTime) ───────────────

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// ── Types ────────────────────────────────────────────────────────────────────

type TenantDetailPageProps = {
  tenant: Tenant;
  currentTab: string;
  onTabChange: (tab: string) => void;
  me: Me;
};

// ── TenantDetailPage ─────────────────────────────────────────────────────────

export function TenantDetailPage({ tenant, currentTab, onTabChange }: TenantDetailPageProps) {
  return (
    <div className="page">
      <TenantHeader tenant={tenant} />
      <TenantTabs current={currentTab} onChange={onTabChange} />
      <div className="stack-4">
        {currentTab === 'overview' && <TenantOverview tenant={tenant} />}
        {currentTab === 'webauthn' && <WebauthnConfigTab tenant={tenant} />}
        {currentTab === 'aaguid' && <AaguidPolicyTab tenant={tenant} />}
        {currentTab === 'apikeys' && <ApiKeysTab tenant={tenant} />}
        {currentTab === 'credentials' && <div className="card"><div className="card__body">Credentials — Task 8</div></div>}
        {currentTab === 'audit' && <div className="card"><div className="card__body">Audit — Task 9</div></div>}
        {currentTab === 'funnel' && <div className="card"><div className="card__body">Funnel — Task 10</div></div>}
      </div>
    </div>
  );
}

// ── TenantHeader — design pages-2.jsx TenantHeader 1:1 포팅 ─────────────────

function TenantHeader({ tenant }: { tenant: Tenant }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 20, gap: 16 }}>
      <div className="row" style={{ gap: 14, alignItems: 'flex-start' }}>
        <div style={{ width: 44, height: 44, borderRadius: 10, background: 'linear-gradient(135deg, var(--accent), var(--accent-hover))', color: 'white', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 20, letterSpacing: '-0.02em' }}>
          {tenant.name.slice(0, 1)}
        </div>
        <div className="stack-1">
          <div className="row" style={{ gap: 8 }}>
            <h1 className="page__title" style={{ marginBottom: 0 }}>{tenant.name}</h1>
            <StatusBadge status={tenant.status} />
          </div>
          <div className="row" style={{ gap: 16, fontSize: 12, color: 'var(--text-mute)', marginTop: 4 }}>
            <span className="mono">{tenant.id}</span>
            <span>·</span>
            <span className="mono">slug: {tenant.slug}</span>
            <span>·</span>
            <span>생성 {fmtDateTime(tenant.createdAt)}</span>
          </div>
        </div>
      </div>
      <div className="row">
        <button className="btn btn--sm" onClick={() => {}}><Icons.ExternalLink size={12} /> RP 사이트 열기</button>
        <button className="btn btn--sm" onClick={() => {}}><Icons.Refresh size={12} /> Refresh</button>
        <button className="btn btn--sm" onClick={() => {}}><Icons.Dots size={14} /></button>
      </div>
    </div>
  );
}

// ── TenantTabs — design pages-2.jsx TenantTabs 1:1 포팅 ─────────────────────

function TenantTabs({ current, onChange }: { current: string; onChange: (tab: string) => void }) {
  const tabs = [
    { id: 'overview', label: '개요' },
    { id: 'webauthn', label: 'WebAuthn' },
    { id: 'aaguid', label: 'AAGUID 정책' },
    { id: 'apikeys', label: 'API Keys' },
    { id: 'credentials', label: 'Credentials' },
    { id: 'audit', label: 'Audit Logs' },
    { id: 'funnel', label: 'Funnel' },
  ];
  return (
    <div className="tabs">
      {tabs.map((t) => (
        <button key={t.id} className={`tabs__btn ${current === t.id ? 'tabs__btn--active' : ''}`} onClick={() => onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

// ── TenantDetailRoute — 라우터 wrapper (App.tsx 에서 사용) ───────────────────

export default function TenantDetailRoute({ me }: { me: Me }) {
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') || 'overview';
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [loading, setLoading] = useState(true);
  const toast = useToast();

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    tenantsApi.get(id)
      .then(setTenant)
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'Tenant 로드 실패', message: msg });
        setTenant(null);
      })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  if (!tenant) return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Tenant 를 찾을 수 없습니다.</div>;

  return (
    <TenantDetailPage
      tenant={tenant}
      currentTab={tab}
      onTabChange={(t) => setSearchParams({ tab: t })}
      me={me}
    />
  );
}
