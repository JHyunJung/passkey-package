import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import type { Tenant } from '@/api/designTypes';
import { auditChainApi, type TenantChainVerify } from '@/api/auditChain';
import { funnelApi, type FunnelData } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { useToast } from '@/shell/ToastHost';
import { adaptFeedItems, type RecentActivityEvent } from './recentActivityAdapter';

// ── Local utilities (mirrors design globals) ────────────────────────────────

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
  const mo = Math.floor(d / 30);
  return `${mo}개월 전`;
}

function tail(s: string, n: number): string {
  return s.slice(-n);
}

function fmt(n: number): string {
  return n.toLocaleString();
}

// ── MetricCard (mirrors design-system MetricCard) ──────────────────────────

function MetricCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div className="card">
      <div className="card__body">
        <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>{label}</div>
        <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em' }}>{value}</div>
        {sub && <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>{sub}</div>}
      </div>
    </div>
  );
}

// ── KV ─────────────────────────────────────────────────────────────────────

function KV({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "120px 1fr", gap: 12, alignItems: "center", fontSize: 13 }}>
      <div className="muted" style={{ fontSize: 12 }}>{k}</div>
      <div>{v}</div>
    </div>
  );
}

// ── EventDot ───────────────────────────────────────────────────────────────

function EventDot({ type }: { type: string }) {
  const map: Record<string, [string, string]> = {
    CREDENTIAL_AUTHENTICATED: ["var(--success)", "var(--success-soft)"],
    CREDENTIAL_REGISTERED: ["var(--info)", "var(--info-soft)"],
    CREDENTIAL_REVOKED: ["var(--danger)", "var(--danger-soft)"],
    API_KEY_ISSUED: ["var(--violet)", "var(--violet-soft)"],
    API_KEY_REVOKED: ["var(--danger)", "var(--danger-soft)"],
    WEBAUTHN_CONFIG_UPDATED: ["var(--warning)", "var(--warning-soft)"],
    ATTESTATION_POLICY_UPDATED: ["var(--warning)", "var(--warning-soft)"],
    SIGNATURE_COUNTER_REGRESSION: ["var(--danger)", "var(--danger-soft)"],
    ATTESTATION_TRUST_FAILED: ["var(--danger)", "var(--danger-soft)"],
  };
  const [c, bg] = map[type] || ["var(--text-mute)", "var(--surface-3)"];
  return (
    <div style={{ width: 24, height: 24, borderRadius: 6, background: bg, color: c, display: "grid", placeItems: "center", flex: "none" }}>
      <div style={{ width: 6, height: 6, borderRadius: 999, background: c }} />
    </div>
  );
}

// ── ChainStatusCard ────────────────────────────────────────────────────────

function ChainStatusCard({
  state,
  onManualVerify,
  onMonthlyReport,
}: {
  state: TenantChainVerify | null;
  onManualVerify: () => void;
  onMonthlyReport: () => void;
}) {
  if (!state) {
    return (
      <div className="card" style={{ background: "linear-gradient(135deg, var(--surface-2), transparent 60%)" }}>
        <div className="card__body" style={{ display: "flex", gap: 14, alignItems: "center" }}>
          <div style={{ width: 38, height: 38, borderRadius: 10, background: "var(--surface-3)", color: "var(--text-mute)", display: "grid", placeItems: "center", flex: "none" }}>
            <Icons.Shield size={20} />
          </div>
          <div style={{ flex: 1 }}>
            <div className="row" style={{ gap: 8 }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>Audit Hash Chain</div>
              <span className="badge">확인 중</span>
            </div>
            <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>체인 무결성 상태를 불러오는 중입니다.</div>
          </div>
        </div>
      </div>
    );
  }

  if (state.intact) {
    return (
      <div className="card" style={{ background: "linear-gradient(135deg, var(--success-soft), transparent 60%)", borderColor: "color-mix(in oklab, var(--success) 25%, var(--border))" }}>
        <div className="card__body" style={{ display: "flex", gap: 14, alignItems: "center" }}>
          <div style={{ width: 38, height: 38, borderRadius: 10, background: "var(--success)", color: "white", display: "grid", placeItems: "center", flex: "none" }}>
            <Icons.Shield size={20} />
          </div>
          <div style={{ flex: 1 }}>
            <div className="row" style={{ gap: 8 }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>Audit Hash Chain 무결</div>
              <span className="badge badge--success badge--dot">INTACT</span>
            </div>
            <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>마지막 자동 검증 · {timeAgo(state.verifiedAt)} · 위변조 0건</div>
          </div>
          <button className="btn btn--sm" onClick={onManualVerify}><Icons.Hash size={12} /> 수동 검증</button>
          <button className="btn btn--sm" onClick={onMonthlyReport}><Icons.Download size={12} /> 월간 보고서</button>
        </div>
      </div>
    );
  }

  return (
    <div className="card" style={{ background: "linear-gradient(135deg, var(--danger-soft), transparent 60%)", borderColor: "color-mix(in oklab, var(--danger) 25%, var(--border))" }}>
      <div className="card__body" style={{ display: "flex", gap: 14, alignItems: "center" }}>
        <div style={{ width: 38, height: 38, borderRadius: 10, background: "var(--danger)", color: "white", display: "grid", placeItems: "center", flex: "none" }}>
          <Icons.Shield size={20} />
        </div>
        <div style={{ flex: 1 }}>
          <div className="row" style={{ gap: 8 }}>
            <div style={{ fontWeight: 600, fontSize: 14 }}>Audit Hash Chain 위변조 감지</div>
            <span className="badge badge--danger badge--dot">TAMPERED</span>
          </div>
          <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
            마지막 검증 · {timeAgo(state.verifiedAt)}{state.tamperedEntryId ? ` · 위변조 항목: ${tail(state.tamperedEntryId, 8)}` : ''}
          </div>
        </div>
        <button className="btn btn--sm" onClick={onManualVerify}><Icons.Hash size={12} /> 수동 검증</button>
        <button className="btn btn--sm" onClick={onMonthlyReport}><Icons.Download size={12} /> 월간 보고서</button>
      </div>
    </div>
  );
}

// ── TenantOverview ─────────────────────────────────────────────────────────

export default function TenantOverview({ tenant }: { tenant: Tenant }) {
  const navigate = useNavigate();
  const toast = useToast();
  const [chainState, setChainState] = useState<TenantChainVerify | null>(null);
  const [funnel, setFunnel] = useState<FunnelData | null>(null);
  const [recentEvents, setRecentEvents] = useState<RecentActivityEvent[]>([]);

  async function handleManualVerify() {
    try {
      const result = await auditChainApi.verifyTenant(tenant.id);
      setChainState(result);
      toast({
        kind: result.intact ? 'ok' : 'warn',
        title: '수동 검증 완료',
        message: result.intact ? '체인 무결' : '위변조 감지',
      });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '수동 검증 실패', message: msg });
    }
  }

  function handleMonthlyReport() {
    navigate('/audit-chain');
  }

  useEffect(() => {
    auditChainApi.verifyTenant(tenant.id)
      .then(setChainState)
      .catch(() => setChainState(null));
  }, [tenant.id]);

  useEffect(() => {
    let cancelled = false;
    funnelApi.get(tenant.id, 7)
      .then((data) => { if (!cancelled) setFunnel(data); })
      .catch(() => { if (!cancelled) setFunnel(null); });
    return () => { cancelled = true; };
  }, [tenant.id]);

  useEffect(() => {
    // activityApi.fetch signature is (sinceId, category) — there is no
    // server-side tenantId filter, so fetch latest feed and filter client-side.
    let cancelled = false;
    activityApi
      .fetch(null, undefined)
      .then((view) => {
        if (cancelled) return;
        setRecentEvents(
          adaptFeedItems(view)
            .filter((e) => e.tenantId === tenant.id)
            .slice(0, 5)
        );
      })
      .catch(() => { if (!cancelled) setRecentEvents([]); });
    return () => { cancelled = true; };
  }, [tenant.id]);

  const f = funnel ?? {
    registration: { attempts: 0, success: 0, ratio: 0 },
    authentication: { attempts: 0, success: 0, ratio: 0 },
  };

  return (
    <div className="stack-4">
      <div className="grid-4">
        <MetricCard label="등록 Credential" value={fmt(tenant.credentials)} sub="활성 + 회수 포함" />
        <MetricCard label="유효 API Key" value={tenant.apiKeys} sub="ACTIVE 상태" />
        <MetricCard label="등록 성공률 (7d)" value={f.registration.attempts > 0 ? `${(f.registration.ratio * 100).toFixed(1)}%` : '—'} sub={f.registration.attempts > 0 ? `${fmt(f.registration.success)} / ${fmt(f.registration.attempts)} 시도` : 'Phase E3 연결 예정'} />
        <MetricCard label="인증 성공률 (7d)" value={f.authentication.attempts > 0 ? `${(f.authentication.ratio * 100).toFixed(1)}%` : '—'} sub={f.authentication.attempts > 0 ? `${fmt(f.authentication.success)} / ${fmt(f.authentication.attempts)} 시도` : 'Phase E3 연결 예정'} />
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card__head"><h3 className="card__title">WebAuthn 요약</h3><button className="btn btn--sm" onClick={() => navigate(`/tenants/${tenant.id}?tab=webauthn`)}>편집 <Icons.ChevronRight size={12} /></button></div>
          <div className="card__body stack-3">
            <KV k="rpId" v={<span className="mono">{tenant.rpId}</span>} />
            <KV k="rpName" v={tenant.name} />
            <KV k="origins" v={
              <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
                <span className="chip mono">https://{tenant.rpId}</span>
              </div>
            } />
            <KV k="userVerification" v={<span className="badge badge--accent">{tenant.userVerification}</span>} />
            <KV k="attestation" v={<span className="badge">{tenant.attestationConveyance}</span>} />
            <KV k="timeout" v={`${tenant.webauthnTimeoutMs / 1000}s`} />
          </div>
        </div>

        <div className="card">
          <div className="card__head"><h3 className="card__title">최근 활동</h3><button className="btn btn--sm" onClick={() => navigate(`/activity?tenantId=${tenant.id}`)}>전체 보기 <Icons.ChevronRight size={12} /></button></div>
          <div style={{ padding: "0" }}>
            {recentEvents.length === 0 ? (
              <div className="muted" style={{ padding: "20px", fontSize: 13, textAlign: "center" }}>최근 활동 없음</div>
            ) : (
              recentEvents.slice(0, 5).map((e, i) => (
                <div key={i} style={{ display: "flex", gap: 10, padding: "10px 20px", borderBottom: i === 4 ? 0 : "1px solid var(--border)", alignItems: "flex-start" }}>
                  <EventDot type={e.type} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 12, fontWeight: 600 }}>{e.type}</div>
                    <div className="mono muted" style={{ fontSize: 11, marginTop: 2, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{tail(e.subjectId, 16)}</div>
                  </div>
                  <div className="muted" style={{ fontSize: 11 }}>{timeAgo(e.ts)}</div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      <ChainStatusCard state={chainState} onManualVerify={handleManualVerify} onMonthlyReport={handleMonthlyReport} />
    </div>
  );
}
