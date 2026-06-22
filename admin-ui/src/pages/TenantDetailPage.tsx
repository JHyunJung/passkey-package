import { useState, useEffect } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { StatusBadge } from '@/shell/StatusBadge';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import type { Me } from '@/api/types';
import { useToast } from '@/shell/ToastHost';
import { Dialog } from '@/shell/Dialog';
import TenantOverview from '@/pages/tenant/TenantOverview';
import WebauthnConfigTab from '@/pages/tenant/WebauthnConfigTab';
import AaguidPolicyTab from '@/pages/tenant/AaguidPolicyTab';
import ApiKeysTab from '@/pages/tenant/ApiKeysTab';
import CredentialsTab from '@/pages/tenant/CredentialsTab';
import AuditTab from '@/pages/tenant/AuditTab';
import FunnelTab from '@/pages/tenant/FunnelTab';

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
  onReload: () => void;
};

// ── SuspendDialog — 슬러그 타이핑 확인 다이얼로그 ─────────────────────────────

function SuspendDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const [typed, setTyped] = useState('');
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  async function go() {
    if (typed !== tenant.slug) return;
    setBusy(true);
    try {
      await tenantsApi.suspend(tenant.id);
      toast({ kind: 'warn', title: '테넌트가 정지되었습니다.', message: `${tenant.slug} · 모든 API 키 revoke됨` });
      onConfirmed();
      onClose();
      setTyped('');
    } catch (e: unknown) {
      toast({ kind: 'err', title: '정지 실패', message: e instanceof Error ? e.message : String(e) });
    } finally { setBusy(false); }
  }
  return (
    <Dialog open={open} onClose={onClose} title="테넌트 정지"
      sub="정지하면 이 테넌트의 모든 API 키가 revoke되고, 등록·인증 ceremony가 거부됩니다."
      footer={<>
        <button className="btn btn--sm" onClick={onClose}>취소</button>
        <button className="btn btn--sm btn--danger" disabled={busy || typed !== tenant.slug} onClick={go}>정지</button>
      </>}>
      <div style={{ fontSize: 13, color: 'var(--text-mute)', marginBottom: 8 }}>
        확인을 위해 테넌트 슬러그 <span className="mono" style={{ color: 'var(--text)' }}>{tenant.slug}</span> 를 입력하세요.
      </div>
      <input className="input mono" value={typed} onChange={(e) => setTyped(e.target.value)}
        placeholder={tenant.slug} style={{ width: '100%' }} autoFocus />
    </Dialog>
  );
}

// ── TenantDetailPage ─────────────────────────────────────────────────────────

export function TenantDetailPage({ tenant, currentTab, onTabChange, me, onReload }: TenantDetailPageProps) {
  const [suspendOpen, setSuspendOpen] = useState(false);
  const toast = useToast();
  return (
    <div className="page">
      <TenantHeader
        tenant={tenant}
        onSuspend={() => setSuspendOpen(true)}
        onActivate={async () => {
          if (!window.confirm('이 테넌트의 정지를 해제하시겠습니까?')) return;
          try { await tenantsApi.activate(tenant.id); toast({ kind: 'ok', title: '정지 해제됨' }); onReload(); }
          catch (e) { toast({ kind: 'err', title: '해제 실패', message: e instanceof Error ? e.message : String(e) }); }
        }}
      />
      <SuspendDialog tenant={tenant} open={suspendOpen} onClose={() => setSuspendOpen(false)} onConfirmed={onReload} />
      <TenantTabs current={currentTab} onChange={onTabChange} />
      <div className="stack-4">
        {currentTab === 'overview' && <TenantOverview tenant={tenant} />}
        {currentTab === 'webauthn' && <WebauthnConfigTab tenant={tenant} />}
        {currentTab === 'aaguid' && <AaguidPolicyTab tenant={tenant} />}
        {currentTab === 'apikeys' && <ApiKeysTab tenant={tenant} />}
        {currentTab === 'credentials' && <CredentialsTab tenant={tenant} />}
        {currentTab === 'audit' && <AuditTab tenant={tenant} isPlatformOperator={me.role === 'PLATFORM_OPERATOR'} />}
        {currentTab === 'funnel' && <FunnelTab tenant={tenant} />}
      </div>
    </div>
  );
}

// ── TenantHeader — design pages-2.jsx TenantHeader 1:1 포팅 ─────────────────

function TenantHeader({ tenant, onSuspend, onActivate }: { tenant: Tenant; onSuspend: () => void; onActivate: () => void }) {
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
        <button className="btn btn--sm" onClick={() => tenant.rpId && window.open('https://' + tenant.rpId, '_blank', 'noopener,noreferrer')}><Icons.ExternalLink size={12} /> RP 사이트 열기</button>
        {tenant.status === 'ACTIVE' ? (
          <button className="btn btn--sm btn--danger" onClick={onSuspend}>테넌트 정지</button>
        ) : (
          <button className="btn btn--sm" style={{ color: 'var(--success)' }} onClick={onActivate}>정지 해제</button>
        )}
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

  const reload = () => {
    if (!id) return;
    tenantsApi.get(id).then(setTenant).catch(() => {});
  };

  if (loading) return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  if (!tenant) return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Tenant 를 찾을 수 없습니다.</div>;

  return (
    <TenantDetailPage
      tenant={tenant}
      currentTab={tab}
      onTabChange={(t) => setSearchParams({ tab: t })}
      me={me}
      onReload={reload}
    />
  );
}
