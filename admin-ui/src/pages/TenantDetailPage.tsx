// admin-ui/src/pages/TenantDetailPage.tsx
import { useState, useEffect, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import { apiKeysApi } from '@/api/apiKeys';
import type { Tenant, AaguidPolicy, ApiKey } from '@/api/designTypes';
import type { Me } from '@/api/types';
import { describeError } from '@/lib/describeError';
import { ApiError } from '@/api/types';
import { useLoad } from '@/lib/useLoad';
import { illustrations } from '@/assets/illustrations';
import { isPlatform } from '@/me/roles';
import { Alert, Button, ConfirmDialog, EmptyState, LoadError, Menu, PropChip, Skeleton, StatusBadge, Tabs, useToast, type TabItem } from '@/ui';
import TenantOverview from '@/pages/tenant/TenantOverview';
import WebauthnConfigTab from '@/pages/tenant/WebauthnConfigTab';
import AaguidPolicyTab from '@/pages/tenant/AaguidPolicyTab';
import ApiKeysTab from '@/pages/tenant/ApiKeysTab';
import CredentialsTab from '@/pages/tenant/CredentialsTab';
import AuditTab from '@/pages/tenant/AuditTab';
import FunnelTab from '@/pages/tenant/FunnelTab';
import { countExpiringKeys, unverifiedAaguidCount } from '@/pages/tenant/tenantSignals';
import { eulReul } from '@/lib/josa';

// ── util ─────────────────────────────────────────────────────────────────────

/** ISO → KST 날짜 'YYYY-MM-DD'. 값이 없으면 '—', 파싱 불가면 원문. */
export function fmtDateKst(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return new Date(d.getTime() + 9 * 3_600_000).toISOString().slice(0, 10);
}

export const TENANT_TABS: TabItem[] = [
  { id: 'overview', label: '개요' },
  { id: 'webauthn', label: 'WebAuthn' },
  { id: 'aaguid', label: 'AAGUID 정책' },
  { id: 'apikeys', label: 'API 키' },
  { id: 'credentials', label: 'Credentials' },
  { id: 'audit', label: '감사 로그' },
  { id: 'funnel', label: '퍼널' },
];

type TenantDetailPageProps = {
  tenant: Tenant;
  currentTab: string;
  onTabChange: (tab: string) => void;
  me: Me;
  onReload: () => void;
  /** 재조회 실패 안내 등, 상세 위에 띄울 알림. */
  notice?: ReactNode;
};

// ── SuspendDialog — L3 slug 입력 확인 (V3 Dialogs-Tenant) ─────────────────────

function SuspendDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const toast = useToast();
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      level="L3"
      verb="정지"
      title={`${tenant.name}${eulReul(tenant.name)} 정지할까요?`}
      sub="정지하면 이 tenant의 모든 RP 서버 요청이 거부됩니다."
      typeToConfirm={{
        expected: tenant.slug,
        label: <>확인을 위해 slug <span className="mono" style={{ background: 'var(--sunk)', padding: '0 4px', borderRadius: 'var(--r-xs)' }}>{tenant.slug}</span> 를 입력하세요</>,
      }}
      onConfirm={async () => {
        await tenantsApi.suspend(tenant.id);
        toast({ kind: 'warn', title: '테넌트가 정지되었습니다.', message: `${tenant.slug} · 모든 API 키 회수됨` });
        onConfirmed();
      }}
    >
      <Alert tone="bad" title="영향 범위">
        <ul className="stack-1" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          <li>· 유효한 API 키 {tenant.apiKeys}개를 즉시 회수</li>
          <li>· 이 tenant의 패스키 등록·인증 모두 거부</li>
          <li>· 정지를 해제해도 회수된 키는 복구되지 않음</li>
        </ul>
      </Alert>
    </ConfirmDialog>
  );
}

// ── ResumeDialog — L1 확인 ───────────────────────────────────────────────────

function ResumeDialog({ tenant, open, onClose, onConfirmed }: {
  tenant: Tenant; open: boolean; onClose: () => void; onConfirmed: () => void;
}) {
  const toast = useToast();
  return (
    <ConfirmDialog
      open={open}
      onClose={onClose}
      level="L1"
      verb="정지 해제"
      danger={false}
      title="정지 해제"
      sub={`${tenant.name} 의 정지를 해제합니다.`}
      onConfirm={async () => {
        await tenantsApi.activate(tenant.id);
        toast({ kind: 'ok', title: '정지 해제됨' });
        onConfirmed();
      }}
    >
      <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
        이 테넌트의 정지를 해제하시겠습니까? 패스키 등록·인증이 다시 허용됩니다. 정지 때 회수된 API 키는 복구되지 않으므로 필요하면 새로 발급하세요.
      </p>
    </ConfirmDialog>
  );
}

// ── TenantHeader — V2 2행 헤더 ──────────────────────────────────────────────

function TenantHeader({ tenant, canManage, onSuspend, onActivate }: {
  tenant: Tenant; canManage: boolean; onSuspend: () => void; onActivate: () => void;
}) {
  return (
    <>
      <div className="row" style={{ gap: 14 }}>
        <span className="avatar avatar--lg" aria-hidden="true">{tenant.name.slice(0, 1).toUpperCase()}</span>
        <div className="row" style={{ gap: 10, flex: '1 1 280px', minWidth: 0 }}>
          <h1 className="page__title truncate" title={tenant.name}>{tenant.name}</h1>
          <StatusBadge status={tenant.status} />
        </div>
        <div className="row">
          {tenant.rpId && (
            <a className="btn" href={`https://${tenant.rpId}`} target="_blank" rel="noopener noreferrer">
              RP 사이트 열기 <Icons.ExternalLink size={13} />
            </a>
          )}
          {canManage && (tenant.status === 'ACTIVE'
            ? <Menu label="더보기" trigger="button" items={[]} dangerItems={[{ label: '테넌트 정지', danger: true, onSelect: onSuspend }]} />
            : <Menu label="더보기" trigger="button" items={[{ label: '정지 해제', onSelect: onActivate }]} />)}
        </div>
      </div>
      <div className="propchips">
        <PropChip label="ID" value={tenant.id} copy />
        <PropChip label="RP ID" value={tenant.rpId} copy />
        <PropChip label="slug" value={tenant.slug} />
        <PropChip label="생성" value={fmtDateKst(tenant.createdAt)} />
      </div>
    </>
  );
}

// ── TenantDetailPage ─────────────────────────────────────────────────────────

export function TenantDetailPage({ tenant, currentTab, onTabChange, me, onReload, notice }: TenantDetailPageProps) {
  const [suspendOpen, setSuspendOpen] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [aaguidPolicy, setAaguidPolicy] = useState<AaguidPolicy | null>(null);
  const [apiKeys, setApiKeys] = useState<ApiKey[] | null>(null);
  const platform = isPlatform(me);

  // 탭 '확인 필요 N' 배지 신호. tenant 가 바뀌거나(onReload 포함) 처음 열릴 때 1회 조회.
  useEffect(() => {
    let cancelled = false;
    setAaguidPolicy(null);
    setApiKeys(null);
    aaguidPolicyApi.get(tenant.id).then((p) => { if (!cancelled) setAaguidPolicy(p); }).catch(() => {});
    apiKeysApi.list(tenant.id).then((k) => { if (!cancelled) setApiKeys(k); }).catch(() => {});
    return () => { cancelled = true; };
  }, [tenant]);

  const expiringKeyCount = apiKeys ? countExpiringKeys(apiKeys, Date.now()) : undefined;
  const unverified = unverifiedAaguidCount(aaguidPolicy);
  const tabs: TabItem[] = TENANT_TABS.map((t) => {
    if (t.id === 'aaguid' && unverified > 0) return { ...t, badge: { text: `확인 필요 ${unverified}`, reason: `MDS 미확인 AAGUID ${unverified}건` } };
    if (t.id === 'apikeys' && (expiringKeyCount ?? 0) > 0) return { ...t, badge: { text: `확인 필요 ${expiringKeyCount}`, reason: `30일 안에 만료되는 키 ${expiringKeyCount}개` } };
    return t;
  });

  return (
    <div className="page">
      {notice}
      <div className="stack-3" style={{ gap: 14 }}>
        <TenantHeader tenant={tenant} canManage={platform} onSuspend={() => setSuspendOpen(true)} onActivate={() => setResumeOpen(true)} />
        <Tabs ariaLabel="Tenant 탭" items={tabs} active={currentTab} onChange={onTabChange} />
      </div>
      {currentTab === 'overview' && <TenantOverview tenant={tenant} isPlatformOperator={platform} expiringKeyCount={expiringKeyCount} />}
      {currentTab === 'webauthn' && <WebauthnConfigTab tenant={tenant} onSaved={onReload} />}
      {currentTab === 'aaguid' && <AaguidPolicyTab tenant={tenant} onSaved={setAaguidPolicy} />}
      {currentTab === 'apikeys' && <ApiKeysTab tenant={tenant} onChanged={onReload} />}
      {currentTab === 'credentials' && <CredentialsTab tenant={tenant} />}
      {currentTab === 'audit' && <AuditTab tenant={tenant} isPlatformOperator={platform} />}
      {currentTab === 'funnel' && <FunnelTab tenant={tenant} />}
      <SuspendDialog tenant={tenant} open={suspendOpen} onClose={() => setSuspendOpen(false)} onConfirmed={onReload} />
      <ResumeDialog tenant={tenant} open={resumeOpen} onClose={() => setResumeOpen(false)} onConfirmed={onReload} />
    </div>
  );
}

// ── RefreshFailed — 상세를 연 뒤 재조회가 실패했을 때(이전 상세는 유지) ─────────

function RefreshFailed({ error, onRetry, retrying }: { error: unknown; onRetry: () => void; retrying: boolean }) {
  const info = describeError(error);
  return (
    <Alert
      tone="warn"
      title="Tenant 정보를 새로 고치지 못했습니다"
      actions={<Button variant="secondary" size="sm" onClick={onRetry} disabled={retrying}>{retrying ? '다시 시도 중…' : '다시 시도'}</Button>}
    >
      {info.title}. {info.detail} 화면의 내용은 이전에 불러온 상태일 수 있습니다.
    </Alert>
  );
}

// ── TenantNotFound — 404 일 때만 ─────────────────────────────────────────────

function TenantNotFound() {
  return (
    <div className="page">
      <div role="alert">
        <EmptyState
          illustration={illustrations.noResults}
          title="Tenant를 찾을 수 없습니다"
          description="삭제됐거나 주소가 잘못된 tenant입니다."
          action={<Link className="btn btn--primary" to="/tenants">Tenants로 돌아가기</Link>}
        />
      </div>
    </div>
  );
}

// ── TenantDetailRoute — 라우터 wrapper (App.tsx 에서 사용) ───────────────────

export default function TenantDetailRoute({ me }: { me: Me }) {
  const { id = '' } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') || 'overview';
  // useLoad 는 늦게 온 이전 id 응답을 버리고(M14), 재조회(onReload) 중에는 이전 tenant 를 유지한다.
  const load = useLoad(() => tenantsApi.get(id), [id]);
  // 주소가 막 바뀐 경우 이전 tenant 를 새 주소에 그리지 않는다.
  const tenant = load.data && load.data.id === id ? load.data : undefined;

  const notFound = load.status === 'failed' && load.error instanceof ApiError && load.error.httpStatus === 404;
  if (notFound) return <TenantNotFound />;
  if (!tenant && load.status !== 'failed') {
    return <div className="page"><Skeleton rows={6} label="Tenant 정보 불러오는 중" /></div>;
  }
  if (!tenant) {
    return (
      <div className="page">
        <LoadError what="Tenant 정보" error={load.error} onRetry={load.reload} retrying={load.reloading} />
        <div className="row" style={{ justifyContent: 'center' }}>
          <Link className="btn btn--ghost" to="/tenants">‹ Tenant 목록으로</Link>
        </div>
      </div>
    );
  }

  return (
    <TenantDetailPage
      tenant={tenant}
      currentTab={tab}
      onTabChange={(t) => setSearchParams({ tab: t })}
      me={me}
      onReload={load.reload}
      notice={load.status === 'failed' ? <RefreshFailed error={load.error} onRetry={load.reload} retrying={load.reloading} /> : undefined}
    />
  );
}
