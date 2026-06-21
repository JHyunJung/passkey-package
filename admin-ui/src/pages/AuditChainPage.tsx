import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview, type IncidentView } from '@/api/auditChainMonitor';
import { monthlyReportApi } from '@/api/monthlyReport';
import { useToast } from '@/shell/ToastHost';
import { Dialog } from '@/shell/Dialog';
import { ApiError } from '@/api/types';

// ── Utilities ────────────────────────────────────────────────────────────────

function fmt(n: number): string {
  return n.toLocaleString();
}

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

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

// ── MetricCard ────────────────────────────────────────────────────────────────

function MetricCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div className="card" style={{ padding: '16px 20px' }}>
      <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 700, lineHeight: 1 }}>{value}</div>
      {sub && <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>{sub}</div>}
    </div>
  );
}

// ── ChainSparkline ───────────────────────────────────────────────────────────
// Design (pages-5.jsx): receives only `intact: boolean`, generates 24 ticks
// with h = 6 + (i * 7919) % 12, marks ticks 14 & 15 red when !intact.
//
// Server adaptation: server sends `buckets: number[]` (hourly row counts).
// When real buckets are provided, we normalize them to the same height range
// [6, 18] so the visual bar proportions reflect actual activity while keeping
// the tampered-tick red-marker behaviour from the design.

function ChainSparkline({ buckets, intact }: { buckets: number[]; intact: boolean }) {
  const ticks = Array.from({ length: 24 }, (_, i) => i);

  // Normalize bucket counts to bar height in [6, 18].
  // Falls back to the design's deterministic formula when buckets are absent.
  const maxBucket = buckets && buckets.length > 0
    ? Math.max(...buckets, 1)
    : 1;

  function barHeight(i: number): number {
    if (buckets && buckets.length > i) {
      // Normalize: map [0, max] → [6, 18]
      return 6 + Math.round((buckets[i] / maxBucket) * 12);
    }
    // Fallback: deterministic formula from design
    return 6 + (i * 7919) % 12;
  }

  return (
    <div style={{ display: 'flex', gap: 2, alignItems: 'flex-end', height: 18 }}>
      {ticks.map((i) => {
        const broken = !intact && (i === 14 || i === 15);
        const h = barHeight(i);
        return (
          <span key={i} style={{
            width: 4, height: h,
            background: broken ? 'var(--danger)' : 'var(--success)',
            borderRadius: 1, opacity: broken ? 1 : 0.55,
          }} />
        );
      })}
    </div>
  );
}

// ── MonthlyReportDialog ──────────────────────────────────────────────────────

function MonthlyReportDialog({ open, onClose, overview }: {
  open: boolean;
  onClose: () => void;
  overview: ChainOverview;
}) {
  const [from, setFrom] = useState('2026-04-01');
  const [to, setTo] = useState('2026-04-30');
  const toast = useToast();

  async function handleGenerate() {
    try {
      const blob = await monthlyReportApi.download(from, to);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `audit-chain-monthly-${from}-to-${to}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      onClose();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'PDF 생성 실패', message: msg });
    }
  }

  return (
    <Dialog open={open} onClose={onClose} wide title="월간 무결성 보고서 발급"
      sub="기간 내 전체 tenant의 hash chain 검증 결과를 PDF로 묶어 내보냅니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={handleGenerate}>PDF 생성 → 다운로드</button>
      </>}
    >
      <div className="grid-2" style={{ marginBottom: 14 }}>
        <div>
          <label className="label">from</label>
          <input className="input mono" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <label className="label">to</label>
          <input className="input mono" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>
      <div style={{ border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden' }}>
        {overview.tenants.map((c, i) => (
          <div key={c.tenantId ?? 'platform'} style={{ display: 'flex', alignItems: 'center', padding: '8px 14px', borderBottom: i === overview.tenants.length - 1 ? 0 : '1px solid var(--border)', fontSize: 13 }}>
            <input type="checkbox" defaultChecked style={{ marginRight: 10 }} />
            <span style={{ flex: 1 }}>{c.tenantName}</span>
            <span className="mono muted" style={{ fontSize: 11, marginRight: 12 }}>{fmt(c.verifiedRows)} rows</span>
            {c.intact ? <span className="badge badge--success">INTACT</span> : <span className="badge badge--danger">TAMPERED</span>}
          </div>
        ))}
      </div>
      <div style={{ marginTop: 12, padding: 10, background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 6, fontSize: 12, display: 'flex', gap: 8 }}>
        <Icons.Info size={14} />
        <span>보고서는 compliance 감사 응답에 사용 가능합니다. 생성에 약 30초 소요 (v1.1).</span>
      </div>
    </Dialog>
  );
}

// ── AuditChainPage ────────────────────────────────────────────────────────────

export default function AuditChainPage() {
  const [overview, setOverview] = useState<ChainOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showReport, setShowReport] = useState(false);
  const [running, setRunning] = useState(false);
  const [incidents, setIncidents] = useState<IncidentView[]>([]);
  const [incidentError, setIncidentError] = useState(false);
  const [creating, setCreating] = useState(false);
  const [confirmCreate, setConfirmCreate] = useState(false);
  const [resolving, setResolving] = useState<IncidentView | null>(null);
  const [resolveNote, setResolveNote] = useState('');
  const [resolveBusy, setResolveBusy] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();

  // Incident 목록만 재조회(페이지 전체 spinner 없이). 실패를 "비어있음"으로
  // 오인시키면 운영자가 실제 OPEN incident(위변조)를 놓치므로, 실패 시 기존
  // incidents 를 빈 배열로 덮지 않고 에러 플래그만 세운다.
  async function refreshIncidents() {
    try {
      const list = await auditChainMonitorApi.listIncidents();
      setIncidents(list);
      setIncidentError(false);
    } catch {
      setIncidentError(true);
    }
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const o = await auditChainMonitorApi.overview(24);
      setOverview(o);
      // Incident 목록 로드 실패는 페이지 전체를 막지 않는다(독립 처리).
      await refreshIncidents();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'overview 로드 실패';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function handleRunAll() {
    setRunning(true);
    try {
      const r = await auditChainMonitorApi.backfill();
      toast({ kind: 'ok', title: '백필 완료', message: `${r.tenantsProcessed} tenants · ${r.rowsUpdated} updated` });
      await load();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '백필 실패';
      toast({ kind: 'err', title: '백필 실패', message: msg });
    } finally {
      setRunning(false);
    }
  }

  function openTenant(tenantId: string | null) {
    if (tenantId) navigate(`/tenants/${tenantId}`);
  }

  async function handleCreateIncident(tenantId: string) {
    setCreating(true);
    try {
      await auditChainMonitorApi.createIncident(tenantId);
      setConfirmCreate(false);
      toast({ kind: 'ok', title: 'Incident 생성됨', message: '보안팀 대응 incident 가 등록되었습니다.' });
      await load();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        // 다른 운영자가 이미 OPEN incident 를 만든 stale 상태일 수 있다 — 닫고 목록 동기화.
        setConfirmCreate(false);
        toast({ kind: 'warn', title: '이미 진행 중', message: '해당 tenant 에 OPEN 상태 incident 가 이미 있습니다.' });
      } else if (e instanceof ApiError && e.status === 422) {
        toast({ kind: 'warn', title: '위변조 미확인', message: '재검증 결과 위변조가 확인되지 않아 incident 를 생성하지 않았습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : 'incident 생성 실패';
        toast({ kind: 'err', title: 'Incident 생성 실패', message: msg });
      }
      // 어느 실패 경로든 서버 상태와 목록을 동기화(409 의 stale OPEN incident 노출).
      await refreshIncidents();
    } finally {
      setCreating(false);
    }
  }

  async function handleResolveIncident() {
    if (!resolving || !resolveNote.trim()) return;
    setResolveBusy(true);
    try {
      await auditChainMonitorApi.resolveIncident(resolving.id, resolveNote.trim());
      setResolving(null);
      setResolveNote('');
      toast({ kind: 'ok', title: '해결됨', message: 'incident 가 RESOLVED 로 표시되었습니다.' });
      await load();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        // 이미 RESOLVED — stale 다이얼로그를 닫고 목록 동기화.
        setResolving(null);
        setResolveNote('');
        toast({ kind: 'warn', title: '이미 해결됨', message: '해당 incident 는 이미 RESOLVED 상태입니다.' });
      } else if (e instanceof ApiError && e.status === 404) {
        setResolving(null);
        setResolveNote('');
        toast({ kind: 'warn', title: '대상 없음', message: 'incident 를 찾을 수 없습니다.' });
      } else {
        const msg = e instanceof Error ? e.message : 'incident 해결 실패';
        toast({ kind: 'err', title: '해결 실패', message: msg });
      }
      await refreshIncidents();
    } finally {
      setResolveBusy(false);
    }
  }

  if (loading) return <div className="page" style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;

  if (error || !overview) {
    return (
      <div className="page">
        <div className="card" style={{ borderColor: 'var(--danger)', background: 'var(--danger-soft)', margin: 24 }}>
          <div className="card__body">
            <div style={{ fontWeight: 600, color: 'var(--danger)' }}>overview 로드 실패</div>
            <div style={{ fontSize: 13, color: 'var(--text)', marginTop: 4 }}>{error}</div>
            <button className="btn btn--sm" style={{ marginTop: 12 }} onClick={() => void load()}>
              <Icons.Refresh size={12} /> 다시 시도
            </button>
          </div>
        </div>
      </div>
    );
  }

  const { totals, tenants } = overview;
  const tamperedTenant = tenants.find((c) => !c.intact) ?? null;

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Audit Chain Monitor</h1>
          <div className="page__sub">전체 tenant의 SHA-256 hash chain 무결성 상태. scheduler가 60초마다 자동 검증합니다.</div>
        </div>
        <div className="row">
          <button className="btn btn--sm" onClick={() => setShowReport(true)}><Icons.Download size={12} /> 월간 보고서</button>
          <button className="btn btn--primary btn--sm" onClick={() => void handleRunAll()} disabled={running}>
            <Icons.Hash size={12} /> {running ? '검증 중…' : '전체 즉시 검증'}
          </button>
        </div>
      </div>

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <MetricCard label="무결 / 전체" value={`${totals.tenantsIntact} / ${totals.tenantsTotal}`} sub={tamperedTenant ? `위변조 의심: ${tamperedTenant.tenantName}` : '전체 무결'} />
        <MetricCard label="검증된 audit row" value={fmt(totals.verifiedRows)} sub="누적 chain length" />
        <MetricCard label="검증 주기" value="60s" sub="background scheduler · prometheus 연동" />
        <MetricCard label="평균 chain 검증" value={`${totals.verificationMs}ms`} sub="100k rows 기준 · p99 920ms" />
      </div>

      {tamperedTenant && (
        <div className="card" style={{ borderColor: 'var(--danger)', background: 'var(--danger-soft)', marginBottom: 20 }}>
          <div className="card__body" style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <div style={{ width: 38, height: 38, borderRadius: 10, background: 'var(--danger)', color: 'white', display: 'grid', placeItems: 'center', flex: 'none' }}>
              <Icons.Alert size={20} />
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, color: 'var(--danger)' }}>위변조 의심 — 즉시 조사 필요</div>
              <div style={{ fontSize: 13, color: 'var(--text)', marginTop: 4 }}>
                <strong>{tamperedTenant.tenantName}</strong> tenant에서 audit row의 hash가 일치하지 않습니다. DBA + 보안팀 알림 필요.
              </div>
            </div>
            <button className="btn" onClick={() => openTenant(tamperedTenant.tenantId)}>tenant 열기 <Icons.ChevronRight size={12} /></button>
            <button
              className="btn btn--danger btn--sm"
              onClick={() => setConfirmCreate(true)}
              disabled={!tamperedTenant.tenantId}
              title={tamperedTenant.tenantId ? undefined : '플랫폼 체인은 incident 대상이 아닙니다'}
            >
              <Icons.Alert size={12} /> Incident 생성
            </button>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card__head">
          <h3 className="card__title">Tenant별 Chain 상태</h3>
          <div className="row">
            <span className="muted" style={{ fontSize: 12 }}>최근 60초 기준</span>
            <span className="badge badge--success badge--dot">INTACT {totals.tenantsIntact}</span>
            {totals.tenantsTampered > 0 && <span className="badge badge--danger badge--dot">TAMPERED {totals.tenantsTampered}</span>}
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Tenant</th>
              <th>Status</th>
              <th style={{ textAlign: 'right' }}>Verified Rows</th>
              <th>마지막 검증</th>
              <th>Chain (시각화)</th>
              <th style={{ textAlign: 'right' }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {tenants.map((c) => (
              <tr key={c.tenantId ?? 'platform'}>
                <td>
                  <button onClick={() => openTenant(c.tenantId)} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'transparent', border: 0, padding: 0, cursor: 'pointer', color: 'var(--text)' }}>
                    <div style={{ width: 22, height: 22, borderRadius: 5, background: 'var(--accent-soft)', color: 'var(--accent)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 10, flex: 'none' }}>{c.tenantName.slice(0, 1)}</div>
                    <span style={{ fontWeight: 600, fontSize: 13 }}>{c.tenantName}</span>
                  </button>
                </td>
                <td>
                  {c.intact ? (
                    <span className="badge badge--success badge--dot">INTACT</span>
                  ) : (
                    <span className="badge badge--danger badge--dot">TAMPERED</span>
                  )}
                </td>
                <td style={{ textAlign: 'right' }} className="mono">{fmt(c.verifiedRows)}</td>
                <td className="muted">{timeAgo(overview.verifiedAt)}</td>
                <td><ChainSparkline buckets={c.buckets} intact={c.intact} /></td>
                <td style={{ textAlign: 'right' }}>
                  <button className="btn btn--xs" onClick={() => openTenant(c.tenantId)}>열기</button>
                  <button className="btn btn--xs" style={{ marginLeft: 4 }}>검증</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ marginTop: 20 }}>
        <div className="card__head">
          <h3 className="card__title">보안 Incident</h3>
          <div className="row">
            <span className="muted" style={{ fontSize: 12 }}>위변조 감지 시 등록되는 대응 incident</span>
            {incidents.some((i) => i.status === 'OPEN') && (
              <span className="badge badge--danger badge--dot">
                OPEN {incidents.filter((i) => i.status === 'OPEN').length}
              </span>
            )}
          </div>
        </div>
        {incidentError ? (
          <div className="card__body" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12, padding: '24px 0' }}>
            <span style={{ color: 'var(--danger)', fontSize: 13, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Icons.Alert size={14} /> Incident 목록을 불러오지 못했습니다.
              {incidents.length > 0 && <span className="muted" style={{ fontSize: 12 }}>(아래는 마지막으로 조회된 목록)</span>}
            </span>
            <button className="btn btn--sm" onClick={() => void refreshIncidents()}>
              <Icons.Refresh size={12} /> 다시 시도
            </button>
          </div>
        ) : null}
        {incidentError && incidents.length === 0 ? null : !incidentError && incidents.length === 0 ? (
          <div className="card__body muted" style={{ fontSize: 13, textAlign: 'center', padding: '28px 0' }}>
            등록된 incident가 없습니다.
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>상태</th>
                <th>Tenant</th>
                <th>유형</th>
                <th>생성시각</th>
                <th>생성자</th>
                <th style={{ textAlign: 'right' }}>액션</th>
              </tr>
            </thead>
            <tbody>
              {incidents.map((inc) => (
                <tr key={inc.id}>
                  <td>
                    {inc.status === 'OPEN' ? (
                      <span className="badge badge--danger badge--dot">OPEN</span>
                    ) : (
                      <span className="badge badge--dot">RESOLVED</span>
                    )}
                  </td>
                  <td style={{ fontWeight: 600, fontSize: 13 }}>{inc.tenantName}</td>
                  <td className="mono" style={{ fontSize: 12 }}>{inc.type}</td>
                  <td className="muted" style={{ fontSize: 12 }}>{formatDateTime(inc.createdAt)}</td>
                  <td className="muted" style={{ fontSize: 12 }}>{inc.createdByEmail}</td>
                  <td style={{ textAlign: 'right' }}>
                    {inc.status === 'OPEN' && (
                      <button
                        className="btn btn--xs"
                        onClick={() => { setResolving(inc); setResolveNote(''); }}
                      >
                        <Icons.Check size={11} /> 해결
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Dialog
        open={confirmCreate}
        onClose={() => { if (!creating) setConfirmCreate(false); }}
        title="Incident 생성"
        sub="위변조가 감지된 tenant 에 대한 보안 대응 incident 를 등록합니다."
        footer={<>
          <button className="btn" onClick={() => setConfirmCreate(false)} disabled={creating}>취소</button>
          <button
            className="btn btn--danger"
            onClick={() => { if (tamperedTenant?.tenantId) void handleCreateIncident(tamperedTenant.tenantId); }}
            disabled={creating || !tamperedTenant?.tenantId}
          >
            {creating ? '생성 중…' : 'Incident 생성'}
          </button>
        </>}
      >
        <div style={{ fontSize: 13, lineHeight: 1.6 }}>
          <strong>{tamperedTenant?.tenantName ?? '—'}</strong> tenant 의 audit chain 에서 위변조가
          감지되었습니다. 생성 시점에 서버가 재검증하여 위변조된 entry 를 자동으로 기록합니다.
        </div>
        <div style={{ marginTop: 12, padding: 10, background: 'var(--danger-soft)', color: 'var(--danger)', borderRadius: 6, fontSize: 12, display: 'flex', gap: 8 }}>
          <Icons.Alert size={14} />
          <span>이미 진행 중인 incident 가 있거나 재검증 결과 위변조가 아니면 생성되지 않습니다.</span>
        </div>
      </Dialog>

      <Dialog
        open={resolving !== null}
        onClose={() => { if (!resolveBusy) { setResolving(null); setResolveNote(''); } }}
        title="Incident 해결"
        sub={resolving ? `${resolving.tenantName} · ${resolving.type}` : undefined}
        footer={<>
          <button className="btn" onClick={() => { setResolving(null); setResolveNote(''); }} disabled={resolveBusy}>취소</button>
          <button
            className="btn btn--primary"
            onClick={() => void handleResolveIncident()}
            disabled={resolveBusy || resolveNote.trim().length === 0}
          >
            {resolveBusy ? '처리 중…' : '해결로 표시'}
          </button>
        </>}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
          <label className="label">해결 메모 (필수)</label>
          <span className="muted" style={{ fontSize: 11 }}>{resolveNote.length}/1024</span>
        </div>
        <textarea
          className="input"
          rows={4}
          maxLength={1024}
          value={resolveNote}
          onChange={(e) => setResolveNote(e.target.value)}
          placeholder="조사 결과 및 조치 내용을 기록하세요."
          style={{ resize: 'vertical' }}
        />
      </Dialog>

      <MonthlyReportDialog open={showReport} onClose={() => setShowReport(false)} overview={overview} />
    </div>
  );
}
