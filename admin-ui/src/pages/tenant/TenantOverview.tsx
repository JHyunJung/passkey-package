// admin-ui/src/pages/tenant/TenantOverview.tsx
import { useState, useEffect, useRef, CSSProperties } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import type { AuditEvent, Tenant } from '@/api/designTypes';
import { auditChainApi, type TenantChainVerify } from '@/api/auditChain';
import { funnelApi, type FunnelStage } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { auditApi } from '@/api/audit';
import { describeError } from '@/lib/describeError';
import { errorToast } from '@/lib/errorToast';
import { useLoad } from '@/lib/useLoad';
import { Alert, Badge, Button, Card, CardBody, CardHeader, KpiCard, LoadError, StatusStrip, useToast } from '@/ui';
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

/** 체인 검증 응답을 이 시간까지 기다린 뒤 "확인 불가"로 바꾼다(V3 개요 — "확인 중"에 멈추지 않음). */
export const CHAIN_TIMEOUT_MS = 15_000;

type ChainView =
  | { kind: 'checking' }
  | { kind: 'done'; r: TenantChainVerify }
  | { kind: 'unavailable'; reason: string };

// ── ChainSummaryCard — 무결 · 위변조 · 확인 중 · 확인 불가 + 검증 중 ─────────────

function ChainSummaryCard({ view, verifying, recentRuns, onRetry, onManualVerify, onMonthlyReport, onDetail }: {
  view: ChainView;
  verifying: boolean;
  recentRuns?: ChainRun[];
  onRetry: () => void;
  onManualVerify: () => void;
  onMonthlyReport: () => void;
  onDetail: () => void;
}) {
  return (
    <Card>
      <CardHeader title="감사 체인" actions={<Button variant="ghost" size="sm" onClick={onDetail}>자세히 <Icons.ChevronRight size={12} /></Button>} />
      <CardBody className="stack-3">
        {verifying ? (
          <div className="row" style={{ gap: 10 }} aria-busy="true">
            <Badge>검증 중</Badge>
            <span className="hint" style={{ margin: 0 }}>수동 검증을 실행하고 있습니다.</span>
          </div>
        ) : view.kind === 'checking' ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--sunk)', 'var(--mute)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>확인 중</strong>
              <span className="hint" style={{ margin: 0 }}>체인 무결성 상태를 확인하고 있습니다.</span>
            </div>
          </div>
        ) : view.kind === 'unavailable' ? (
          <div className="stack-2">
            <div className="row" style={{ gap: 10 }}>
              <Badge>확인 불가</Badge>
              <strong>검증 결과를 받지 못했습니다</strong>
            </div>
            <span className="hint" style={{ margin: 0 }}>{view.reason} 다시 시도하거나 감사 로그에서 확인하세요.</span>
          </div>
        ) : view.r.intact ? (
          <div className="row" style={{ gap: 12, flexWrap: 'nowrap' }}>
            <span style={iconBox('var(--ok-soft)', 'var(--ok)')} aria-hidden="true"><Icons.Shield size={20} /></span>
            <div className="col" style={{ minWidth: 0 }}>
              <strong>무결 · 위변조 0건</strong>
              <span className="hint" style={{ margin: 0 }}>마지막 검증 {timeAgo(view.r.verifiedAt)}</span>
            </div>
          </div>
        ) : (
          <Alert tone="bad" title="위변조 감지">
            마지막 검증 {timeAgo(view.r.verifiedAt)}{view.r.tamperedEntryId ? ` · 위변조 항목 …${tail(view.r.tamperedEntryId, 8)}` : ''}
          </Alert>
        )}
        {recentRuns && recentRuns.length > 0 && (
          <StatusStrip fluid results={recentRuns.map((r) => r.ok)} label={recentRunsLabel(recentRuns)} />
        )}
        {view.kind === 'unavailable' && !verifying ? (
          <div className="row">
            <Button size="sm" variant="primary" onClick={onRetry}>다시 시도</Button>
            <Button size="sm" variant="ghost" onClick={onDetail}>감사 로그로</Button>
          </div>
        ) : (
          <div className="row">
            <Button size="sm" onClick={onManualVerify} disabled={verifying || view.kind === 'checking'}>{verifying ? '검증 중…' : '수동 검증'}</Button>
            <Button size="sm" onClick={onMonthlyReport} disabled={verifying}>월간 보고서</Button>
          </div>
        )}
      </CardBody>
    </Card>
  );
}

// ── TenantOverview ───────────────────────────────────────────────────────────

type Props = { tenant: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number; chainTimeoutMs?: number };

export default function TenantOverview({ tenant, isPlatformOperator = false, expiringKeyCount, chainTimeoutMs = CHAIN_TIMEOUT_MS }: Props) {
  const navigate = useNavigate();
  const toast = useToast();
  const [chain, setChain] = useState<ChainView>({ kind: 'checking' });
  const [chainAttempt, setChainAttempt] = useState(0);
  const [verifying, setVerifying] = useState(false);
  const verifyingRef = useRef(false);
  const chainSeq = useRef(0); // 최신 검증 요청만 상태를 바꾼다(늦은 응답·타임아웃 이후 응답·재검증에 덮이지 않게)
  const chainTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const toastRef = useRef(toast);
  toastRef.current = toast;
  const [recentEvents, setRecentEvents] = useState<RecentActivityEvent[]>([]);
  const funnel = useLoad(() => funnelApi.get(tenant.id, 7), [tenant.id]);

  /** 자동 검증(manual=false)과 수동 검증(manual=true)이 같은 seq·타이머를 쓴다. 어느 경로로 끝나도 verifying 을 푼다. */
  function runChainVerify(manual: boolean) {
    const my = ++chainSeq.current; // 진행 중이던 이전 요청은 여기서 무효화
    clearTimeout(chainTimer.current);
    verifyingRef.current = manual;
    setVerifying(manual);
    if (!manual) setChain({ kind: 'checking' });
    const settle = () => { clearTimeout(chainTimer.current); verifyingRef.current = false; setVerifying(false); };
    chainTimer.current = setTimeout(() => {
      if (my !== chainSeq.current) return;
      chainSeq.current++; // 이후 도착하는 응답은 버린다
      settle();
      const reason = `${Math.round(chainTimeoutMs / 1000)}초 안에 응답이 없었습니다.`;
      if (manual) toastRef.current({ kind: 'err', title: '수동 검증 실패', message: reason });
      else setChain({ kind: 'unavailable', reason });
    }, chainTimeoutMs);
    auditChainApi.verifyTenant(tenant.id).then(
      (r) => {
        if (my !== chainSeq.current) return;
        settle();
        setChain({ kind: 'done', r });
        if (manual) toastRef.current({ kind: r.intact ? 'ok' : 'warn', title: '수동 검증 완료', message: r.intact ? '체인 무결' : '위변조 감지' });
      },
      (e: unknown) => {
        if (my !== chainSeq.current) return;
        settle();
        if (manual) toastRef.current(errorToast(e, '수동 검증 실패'));
        else setChain({ kind: 'unavailable', reason: `${describeError(e).title}.` });
      },
    );
  }

  function handleManualVerify() {
    if (verifyingRef.current) return; // 같은 틱의 연타까지 막는다
    runChainVerify(true);
  }

  // 감사 체인 검증(verifyTenant)은 PLATFORM 전용 API — RP_ADMIN 에게는 카드 자체를 그리지 않는다.
  useEffect(() => {
    if (!isPlatformOperator) return;
    runChainVerify(false);
    return () => { chainSeq.current++; clearTimeout(chainTimer.current); verifyingRef.current = false; setVerifying(false); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant.id, isPlatformOperator, chainAttempt, chainTimeoutMs]);

  // ★ 최근 검증 이력(recentRuns)은 서버에 아직 없다. 그 필드를 찾으려고 전 tenant 체인을 검증하는
  // overview 를 부르지 않는다 — 서버가 필드를 주면 여기서 읽어 ChainSummaryCard 의 recentRuns 로 넘긴다.

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

  const delta = tenant.credentialsDelta7d;
  const f = funnel.status === 'ok' ? funnel.data : undefined;

  // 실패는 "—" + 원인, 로딩은 "—" 만. "시도 없음"은 조회가 성공했을 때만.
  function rateKpi(label: string, stage: 'registration' | 'authentication', none: string) {
    if (funnel.status === 'failed') return <KpiCard label={label} value="—" hint="불러오지 못했습니다" hintTone="warn" />;
    if (!f) return <KpiCard label={label} value="—" />;
    const s: FunnelStage = f[stage];
    return (
      <KpiCard label={label}
        value={s.attempts > 0 ? `${(s.ratio * 100).toFixed(1)}%` : '—'}
        hint={s.attempts > 0 ? `${fmt(s.success)} / ${fmt(s.attempts)} 시도` : none} />
    );
  }

  return (
    <div className="stack-6">
      <div className="kpis">
        <KpiCard label="등록 Credential" value={fmt(tenant.credentials)}
          hint={delta !== undefined ? `7일 ${delta >= 0 ? '+' : ''}${fmt(delta)}` : undefined} />
        <KpiCard label="유효 API Key" value={tenant.apiKeys}
          hint={expiringKeyCount !== undefined ? `만료 임박 ${expiringKeyCount}` : undefined}
          hintTone={expiringKeyCount ? 'warn' : 'default'} />
        {rateKpi('등록 성공률 (7d)', 'registration', '최근 7일 등록 시도 없음')}
        {rateKpi('인증 성공률 (7d)', 'authentication', '최근 7일 인증 시도 없음')}
      </div>
      {funnel.status === 'failed' && (
        <LoadError compact what="퍼널 지표" error={funnel.error} onRetry={funnel.reload} retrying={funnel.reloading} />
      )}

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
            view={chain}
            verifying={verifying}
            onRetry={() => setChainAttempt((n) => n + 1)}
            onManualVerify={handleManualVerify}
            onMonthlyReport={() => navigate('/audit-chain')}
            onDetail={() => navigate(`/tenants/${tenant.id}?tab=audit`)}
          />
        )}
      </div>
    </div>
  );
}
