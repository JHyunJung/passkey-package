// admin-ui/src/pages/tenant/TenantOverview.tsx
import { useState, useEffect, CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import type { AuditEvent, Tenant } from '@/api/designTypes';
import { auditChainApi, type TenantChainVerify } from '@/api/auditChain';
import { funnelApi, type FunnelData } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { auditApi } from '@/api/audit';
import { Alert, Button, Card, CardBody, CardHeader, KpiCard, StatusStrip, useToast } from '@/ui';
import { adaptFeedItems, type RecentActivityEvent } from './recentActivityAdapter';
import { recentRunsLabel, type ChainRun } from './tenantSignals';

// ── util ─────────────────────────────────────────────────────────────────────

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return '방금 전';
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}일 전`;
  return `${Math.floor(d / 30)}개월 전`;
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString();
}

/** 이벤트 유형 점 색. 유형 이름 태그가 함께 표시되므로 색만으로 의미를 전달하지 않는다. */
function eventColor(type: string): string {
  if (/FAIL|REVOKED|REGRESSION|TAMPER/.test(type)) return 'var(--bad)';
  if (type.startsWith('API_KEY') || type.endsWith('_UPDATED')) return 'var(--warn-dot)';
  return 'var(--brand-700)';
}

function auditToRecent(e: AuditEvent): RecentActivityEvent {
  return {
    id: e.id, ts: e.ts, tenantId: null, tenantName: '', tenantSlug: null, type: e.eventType,
    actorType: e.actorType, actorId: e.actorId, subjectId: e.subjectId ?? '—', category: 'ops',
  };
}

const iconBox = (bg: string, fg: string): CSSProperties => ({
  width: 40, height: 40, borderRadius: 10, background: bg, color: fg,
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none',
});

// ── ChainSummaryCard ─────────────────────────────────────────────────────────

function ChainSummaryCard({ state, recentRuns, onManualVerify, onMonthlyReport, onDetail }: {
  state: TenantChainVerify | null;
  recentRuns?: ChainRun[];
  onManualVerify: () => void;
  onMonthlyReport: () => void;
  onDetail: () => void;
}) {
  return (
    <Card>
      <CardHeader title="감사 체인" actions={<Button variant="ghost" size="sm" onClick={onDetail}>자세히 <Icons.ChevronRight size={12} /></Button>} />
      <CardBody className="stack-3">
        {state === null ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--sunk)', 'var(--mute)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>확인 중</strong>
              <span className="hint" style={{ margin: 0 }}>체인 무결성 상태를 불러오는 중입니다.</span>
            </div>
          </div>
        ) : state.intact ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--ok-soft)', 'var(--ok)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>무결 · 위변조 0건</strong>
              <span className="hint" style={{ margin: 0 }}>마지막 검증 {timeAgo(state.verifiedAt)}</span>
            </div>
          </div>
        ) : (
          <Alert tone="bad" title="위변조 감지">
            마지막 검증 {timeAgo(state.verifiedAt)}{state.tamperedEntryId ? ` · 위변조 항목 …${tail(state.tamperedEntryId, 8)}` : ''}
          </Alert>
        )}
        {recentRuns && recentRuns.length > 0 && (
          <StatusStrip fluid results={recentRuns.map((r) => r.ok)} label={recentRunsLabel(recentRuns)} />
        )}
        <div className="row">
          <Button size="sm" onClick={onManualVerify}>수동 검증</Button>
          <Button size="sm" onClick={onMonthlyReport}>월간 보고서</Button>
        </div>
      </CardBody>
    </Card>
  );
}

// ── TenantOverview ───────────────────────────────────────────────────────────

type Props = { tenant: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number };

export default function TenantOverview({ tenant, isPlatformOperator = false, expiringKeyCount }: Props) {
  const navigate = useNavigate();
  const toast = useToast();
  const [chainState, setChainState] = useState<TenantChainVerify | null>(null);
  const [funnel, setFunnel] = useState<FunnelData | null>(null);
  const [recentEvents, setRecentEvents] = useState<RecentActivityEvent[]>([]);

  async function handleManualVerify() {
    try {
      const result = await auditChainApi.verifyTenant(tenant.id);
      setChainState(result);
      toast({ kind: result.intact ? 'ok' : 'warn', title: '수동 검증 완료', message: result.intact ? '체인 무결' : '위변조 감지' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '수동 검증 실패', message: msg });
    }
  }

  function handleMonthlyReport() {
    navigate('/audit-chain');
  }

  // 감사 체인 검증(verifyTenant)은 PLATFORM 전용 API — RP_ADMIN 에게는 카드 자체를 그리지 않는다.
  useEffect(() => {
    if (!isPlatformOperator) return;
    let cancelled = false;
    auditChainApi.verifyTenant(tenant.id)
      .then((r) => { if (!cancelled) setChainState(r); })
      .catch(() => { if (!cancelled) setChainState(null); });
    return () => { cancelled = true; };
  }, [tenant.id, isPlatformOperator]);

  // ★ 최근 검증 이력(recentRuns, 스펙 §6 ②)은 서버에 아직 없다. 그 필드를 찾으려고 전 tenant 체인을 검증하는
  // overview 를 부르지 않는다 — 서버가 필드를 주면 여기서 읽어 ChainSummaryCard 의 recentRuns 로 넘긴다.

  useEffect(() => {
    let cancelled = false;
    funnelApi.get(tenant.id, 7)
      .then((data) => { if (!cancelled) setFunnel(data); })
      .catch(() => { if (!cancelled) setFunnel(null); });
    return () => { cancelled = true; };
  }, [tenant.id]);

  useEffect(() => {
    let cancelled = false;
    // PLATFORM: 활동 피드(PLATFORM 전용)에서 이 tenant 만. RP_ADMIN: 이 tenant 의 감사 로그 최근 5건.
    const load: Promise<RecentActivityEvent[]> = isPlatformOperator
      ? activityApi.fetch(null, undefined).then((view) => adaptFeedItems(view).filter((e) => e.tenantId === tenant.id).slice(0, 5))
      : auditApi.list(tenant.id, 0, 5).then((res) => res.items.slice(0, 5).map(auditToRecent));
    load
      .then((events) => { if (!cancelled) setRecentEvents(events); })
      .catch(() => { if (!cancelled) setRecentEvents([]); });
    return () => { cancelled = true; };
  }, [tenant.id, isPlatformOperator]);

  const f = funnel ?? {
    registration: { attempts: 0, success: 0, ratio: 0 },
    authentication: { attempts: 0, success: 0, ratio: 0 },
  };
  const delta = tenant.credentialsDelta7d;

  return (
    <div className="stack-6">
      <div className="kpis">
        <KpiCard label="등록 Credential" value={fmt(tenant.credentials)}
          hint={delta !== undefined ? `7일 ${delta >= 0 ? '+' : ''}${fmt(delta)}` : undefined} />
        <KpiCard label="유효 API Key" value={tenant.apiKeys}
          hint={expiringKeyCount !== undefined ? `만료 임박 ${expiringKeyCount}` : undefined}
          hintTone={expiringKeyCount ? 'warn' : 'default'} />
        <KpiCard label="등록 성공률 (7d)"
          value={f.registration.attempts > 0 ? `${(f.registration.ratio * 100).toFixed(1)}%` : '—'}
          hint={f.registration.attempts > 0 ? `${fmt(f.registration.success)} / ${fmt(f.registration.attempts)} 시도` : '최근 7일 등록 시도 없음'} />
        <KpiCard label="인증 성공률 (7d)"
          value={f.authentication.attempts > 0 ? `${(f.authentication.ratio * 100).toFixed(1)}%` : '—'}
          hint={f.authentication.attempts > 0 ? `${fmt(f.authentication.success)} / ${fmt(f.authentication.attempts)} 시도` : '최근 7일 인증 시도 없음'} />
      </div>

      <div className={isPlatformOperator ? 'grid-3' : 'grid-2'}>
        <Card>
          <CardHeader title="WebAuthn 요약" actions={
            <Button variant="ghost" size="sm" onClick={() => navigate(`/tenants/${tenant.id}?tab=webauthn`)}>편집 <Icons.ChevronRight size={12} /></Button>
          } />
          <CardBody>
            <dl className="kv" style={{ gridTemplateColumns: '120px minmax(0, 1fr)' }}>
              <dt>rpId</dt><dd className="mono truncate" style={{ fontSize: 12 }} title={tenant.rpId}>{tenant.rpId}</dd>
              <dt>rpName</dt><dd className="truncate" title={tenant.name}>{tenant.name}</dd>
              {tenant.allowedOrigins && (
                <><dt>origins</dt><dd className="row" style={{ gap: 4 }}>{tenant.allowedOrigins.map((o) => <span key={o} className="tag">{o}</span>)}</dd></>
              )}
              <dt>userVerification</dt><dd><span className="tag">{tenant.userVerification}</span></dd>
              <dt>attestation</dt><dd><span className="tag">{tenant.attestationConveyance}</span></dd>
              <dt>timeout</dt><dd className="mono" style={{ fontSize: 12 }}>{tenant.webauthnTimeoutMs} ms</dd>
            </dl>
          </CardBody>
        </Card>

        <Card>
          <CardHeader title="최근 활동" actions={
            <Button variant="ghost" size="sm" onClick={() => navigate(isPlatformOperator ? `/activity?tenantId=${tenant.id}` : `/tenants/${tenant.id}?tab=audit`)}>전체 보기 <Icons.ChevronRight size={12} /></Button>
          } />
          {recentEvents.length === 0 ? (
            <p className="muted" style={{ margin: 0, padding: 20, fontSize: 13, textAlign: 'center' }}>최근 활동 없음</p>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {recentEvents.map((e, i) => (
                <li key={e.id} className="row" style={{ flexWrap: 'nowrap', gap: 10, padding: '11px 20px', borderBottom: i === recentEvents.length - 1 ? 0 : '1px solid var(--line-soft)' }}>
                  <span className="dot" style={{ background: eventColor(e.type) }} aria-hidden="true" />
                  <span className="tag" style={{ flex: 'none' }}>{e.type}</span>
                  <span className="mono hint truncate" style={{ margin: 0, flex: '1 1 0' }}>…{tail(e.subjectId, 16)}</span>
                  <span className="hint" style={{ margin: 0, flex: 'none' }}>{timeAgo(e.ts)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        {isPlatformOperator && (
          <ChainSummaryCard
            state={chainState}
            onManualVerify={() => void handleManualVerify()}
            onMonthlyReport={handleMonthlyReport}
            onDetail={() => navigate(`/tenants/${tenant.id}?tab=audit`)}
          />
        )}
      </div>
    </div>
  );
}
