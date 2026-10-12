import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview, type IncidentView } from '@/api/auditChainMonitor';
import { auditChainApi } from '@/api/auditChain';
import { monthlyReportApi } from '@/api/monthlyReport';
import { ApiError } from '@/api/types';
import { statusLabel } from '@/i18n/labels';
import { kstDateTime, kstPrevMonthRange } from '@/lib/kst';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { illustrations } from '@/assets/illustrations';
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, Field, KpiCard, LoadError, PageHeader,
  Segmented, Skeleton, StatusStrip, useToast, type Column,
} from '@/ui';

type ChainRow = ChainOverview['tenants'][number];
const STRIP_RUNS = 14;

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

function timeUntil(iso: string): string {
  const m = Math.ceil((new Date(iso).getTime() - Date.now()) / 60000);
  if (m <= 0) return '곧';
  if (m < 60) return `${m}분 후`;
  return `${Math.floor(m / 60)}시간 후`;
}

function stripLabel(runs: { ok: boolean }[]): string {
  const bad = runs.filter((r) => !r.ok).length;
  return bad === 0 ? `최근 ${runs.length}회 모두 정상` : `최근 ${runs.length}회 중 위변조 감지 ${bad}회`;
}

// ── MonthlyReportDialog ──────────────────────────────────────────────────────
// 서버 monthly-report 는 from/to 만 받는다(tenant 선택 없음) → 포함 tenant 는 읽기 전용 목록.

function MonthlyReportDialog({ open, onClose, overview }: {
  open: boolean;
  onClose: () => void;
  overview: ChainOverview;
}) {
  const initial = kstPrevMonthRange(new Date());
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [busy, setBusy] = useState(false);
  const toast = useToast();

  async function handleGenerate() {
    setBusy(true);
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
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} wide title="월간 무결성 보고서 발급"
      sub="기간 내 전체 tenant 의 chain 검증 결과를 PDF 로 묶습니다."
      footer={<>
        <Button onClick={onClose} disabled={busy}>취소</Button>
        <Button variant="primary" onClick={() => void handleGenerate()} disabled={busy || !from || !to}>
          {busy ? '생성 중…' : 'PDF 생성 → 다운로드'}
        </Button>
      </>}
    >
      <div className="grid-2" style={{ gap: 12 }}>
        <Field label="시작일" htmlFor="report-from">
          <input id="report-from" className="input mono" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="종료일" htmlFor="report-to">
          <input id="report-to" className="input mono" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
      </div>
      <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
        <div style={{ padding: '10px 14px', background: 'var(--canvas)', borderBottom: '1px solid var(--line-soft)', fontSize: 12, fontWeight: 600, color: 'var(--mute)' }}>
          {`포함 tenant · ${overview.tenants.length}개`}
        </div>
        <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {overview.tenants.map((c) => (
            <li key={c.tenantId ?? 'platform'} className="row" style={{ gap: 10, flexWrap: 'nowrap', padding: '10px 14px', borderBottom: '1px solid var(--line-soft)', fontSize: 13 }}>
              <span className="truncate" style={{ flex: '1 1 0' }} title={c.tenantName}>{c.tenantName}</span>
              <span className="mono muted" style={{ fontSize: 12 }}>{`${fmt(c.verifiedRows)} rows`}</span>
              <Badge tone={c.intact ? 'ok' : 'bad'}>{statusLabel(c.intact ? 'INTACT' : 'TAMPERED')}</Badge>
            </li>
          ))}
        </ul>
      </div>
    </Dialog>
  );
}

// ── AuditChainPage ────────────────────────────────────────────────────────────

const PAGE_TITLE = '감사 체인 모니터';
// 스케줄러 없음: overview 호출 시마다 서버가 전체 tenant chain 을 검증한다(AuditChainMonitorController.overview).
const PAGE_SUB = "tenant별 감사 로그 해시 체인 무결성 — 화면을 열 때와 '지금 검증'을 누를 때 서버가 검증합니다.";

/** 아바타 글자 — 첫 문자/숫자(예: "[platform]" → "P"). */
function initial(name: string): string {
  const m = name.match(/[\p{L}\p{N}]/u);
  return (m ? m[0] : name.slice(0, 1)).toUpperCase();
}

export default function AuditChainPage() {
  // overview 호출 = 서버 전체 검증. reload() 중에는 이전 결과(data)를 그대로 보여 준다.
  const chain = useLoad(() => auditChainMonitorApi.overview(24), []);
  const overview = chain.data;
  const [showReport, setShowReport] = useState(false);
  const [backfillOpen, setBackfillOpen] = useState(false);
  const [backfilling, setBackfilling] = useState(false);
  const backfillCancelRef = useRef<HTMLButtonElement>(null);
  const [incidents, setIncidents] = useState<IncidentView[]>([]);
  const [incidentError, setIncidentError] = useState(false);
  const [creating, setCreating] = useState(false);
  const creatingRef = useRef(false);
  const [createTarget, setCreateTarget] = useState<ChainRow | null>(null);
  const [resolving, setResolving] = useState<IncidentView | null>(null);
  const [resolveNote, setResolveNote] = useState('');
  const [resolveBusy, setResolveBusy] = useState(false);
  const [view, setView] = useState<'all' | 'tampered'>('all');
  const [verifyingId, setVerifyingId] = useState<string | null>(null);
  const navigate = useNavigate();
  const toast = useToast();

  // Incident 목록만 재조회(페이지 전체 spinner 없이). 실패를 "비어있음"으로
  // 오인시키면 운영자가 실제 OPEN incident(위변조)를 놓치므로, 실패 시 기존
  // incidents 를 빈 배열로 덮지 않고 에러 플래그만 세운다.
  const incidentsSeq = useRef(0);
  async function refreshIncidents() {
    const my = ++incidentsSeq.current;
    try {
      const list = await auditChainMonitorApi.listIncidents();
      if (my !== incidentsSeq.current) return; // 늦게 끝난 이전 요청은 버린다
      setIncidents(list);
      setIncidentError(false);
    } catch {
      if (my !== incidentsSeq.current) return;
      setIncidentError(true);
    }
  }

  // Incident 목록 로드 실패는 페이지 전체를 막지 않는다(독립 처리).
  useEffect(() => { void refreshIncidents(); }, []);

  /** 다시 검증 — 화면은 이전 결과를 유지하고(chain.reloading), 끝난 결과로 바꾼다. */
  function reverify() {
    chain.reload();
    void refreshIncidents();
  }

  // 체인 백필은 검증이 아니다: 링크가 맞지 않는 행의 hash 를 다시 계산해 덮어쓰고 이후를 다시 잇는다.
  // 행이 삭제된 체인에 실행하면 공백이 다시 이어져 위변조 흔적이 사라진다 — 확인 다이얼로그 뒤에서만 실행한다.
  async function handleBackfill() {
    setBackfilling(true);
    try {
      const r = await auditChainMonitorApi.backfill();
      setBackfillOpen(false);
      toast({ kind: 'ok', title: '백필 완료', message: `tenant ${fmt(r.tenantsProcessed)}개 처리 · 행 ${fmt(r.rowsUpdated)}개 갱신` });
      reverify();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '백필 실패';
      toast({ kind: 'err', title: '백필 실패', message: msg });
    } finally {
      setBackfilling(false);
    }
  }

  function openTenant(tenantId: string | null) {
    if (tenantId) navigate(`/tenants/${tenantId}`);
  }

  function openAudit(tenantId: string | null) {
    if (tenantId) navigate(`/tenants/${tenantId}?tab=audit`);
  }

  async function handleVerifyTenant(tenantId: string) {
    setVerifyingId(tenantId);
    try {
      const r = await auditChainApi.verifyTenant(tenantId);
      toast(r.intact
        ? { kind: 'ok', title: '검증 완료', message: 'chain 이 정상입니다.' }
        : { kind: 'err', title: '위변조 의심', message: r.tamperedEntryId ? `불일치 entry ${r.tamperedEntryId}` : 'hash 불일치' });
      reverify();
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '검증하지 못했습니다', message: `${info.title}. ${info.detail}` });
    } finally {
      setVerifyingId(null);
    }
  }

  async function handleCreateIncident(tenantId: string) {
    if (creatingRef.current) return; // 더블 클릭으로 같은 요청이 두 번 나가지 않게
    creatingRef.current = true;
    setCreating(true);
    try {
      await auditChainMonitorApi.createIncident(tenantId);
      setCreateTarget(null);
      toast({ kind: 'ok', title: 'Incident 생성됨', message: '보안팀 대응 incident 가 등록되었습니다.' });
      reverify();
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 409) {
        // 다른 운영자가 이미 OPEN incident 를 만든 stale 상태일 수 있다 — 닫고 목록 동기화.
        setCreateTarget(null);
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
      creatingRef.current = false;
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
      reverify();
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

  // 결과가 한 번도 없을 때만 로딩/실패 화면. 재검증 중·재검증 실패에는 이전 결과를 유지한다.
  if (!overview) {
    return (
      <div className="page">
        <PageHeader title={PAGE_TITLE} sub={PAGE_SUB} />
        <Card>
          {chain.status === 'failed'
            ? <LoadError what="감사 체인 상태" error={chain.error} onRetry={chain.reload} retrying={chain.reloading} />
            : <Skeleton rows={6} />}
        </Card>
      </div>
    );
  }

  const { totals, tenants } = overview;
  const tamperedRows = tenants.filter((c) => !c.intact);
  const tampered = totals.tenantsTampered > 0 || tamperedRows.length > 0;
  const verifying = chain.reloading;
  const openIncidentTenants = new Set(incidents.filter((i) => i.status === 'OPEN').map((i) => i.tenantId));
  const showStrip = tenants.some((c) => c.recentRuns !== undefined);
  const rows = view === 'tampered' ? tamperedRows : tenants;
  const openCount = incidents.filter((i) => i.status === 'OPEN').length;
  const headline = tampered
    ? `${totals.tenantsTampered}개 tenant 위변조 의심 · ${totals.tenantsIntact} / ${totals.tenantsTotal} 정상`
    : `모든 tenant 정상 · ${totals.tenantsIntact} / ${totals.tenantsTotal}`;
  const lastLine = [
    `마지막 검증 ${timeAgo(overview.verifiedAt)}`,
    overview.nextRunAt !== undefined ? `다음 검증 ${timeUntil(overview.nextRunAt)}` : null,
  ].filter(Boolean).join(' · ');

  const chainColumns: Column<ChainRow>[] = [
    {
      key: 'tenant', header: 'Tenant',
      render: (c) => (
        <div className="row" style={{ gap: 10, flexWrap: 'nowrap', minWidth: 0 }}>
          <span className="avatar avatar--sq avatar--sm" aria-hidden="true">{initial(c.tenantName)}</span>
          <strong className="truncate" style={{ fontSize: 13, maxWidth: 260 }} title={c.tenantName}>{c.tenantName}</strong>
        </div>
      ),
    },
    { key: 'status', header: '상태', render: (c) => <Badge tone={c.intact ? 'ok' : 'bad'}>{statusLabel(c.intact ? 'INTACT' : 'TAMPERED')}</Badge> },
    { key: 'rows', header: '검증 행 수', align: 'right', render: (c) => fmt(c.verifiedRows) },
    { key: 'verifiedAt', header: '마지막 검증', render: () => <span className="muted">{timeAgo(overview.verifiedAt)}</span> },
    ...(showStrip ? [{
      key: 'strip',
      header: `최근 ${STRIP_RUNS}회`,
      render: (c: ChainRow) => {
        if (!c.recentRuns || c.recentRuns.length === 0) return null;
        const last = c.recentRuns.slice(-STRIP_RUNS);
        return <StatusStrip results={last.map((r) => r.ok)} label={stripLabel(last)} />;
      },
    }] : []),
    {
      key: 'actions', header: '액션',
      render: (c) => (
        <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <Button size="sm" onClick={() => openTenant(c.tenantId)} disabled={!c.tenantId}>열기</Button>
          <Button size="sm" onClick={() => { if (c.tenantId) void handleVerifyTenant(c.tenantId); }} disabled={!c.tenantId || verifyingId === c.tenantId}>
            {verifyingId === c.tenantId ? '검증 중…' : '검증'}
          </Button>
        </div>
      ),
    },
  ];

  const incidentColumns: Column<IncidentView>[] = [
    { key: 'status', header: '상태', render: (i) => <Badge tone={i.status === 'OPEN' ? 'warn' : 'ok'}>{statusLabel(i.status)}</Badge> },
    { key: 'tenant', header: 'Tenant', render: (i) => <strong style={{ fontSize: 13 }}>{i.tenantName}</strong> },
    { key: 'type', header: '유형', render: (i) => <span className="tag">{i.type}</span> },
    { key: 'createdAt', header: '생성시각', render: (i) => <span className="muted">{kstDateTime(i.createdAt).slice(0, 16)}</span> },
    { key: 'createdBy', header: '생성자', render: (i) => <span className="muted">{i.createdByEmail}</span> },
    {
      key: 'actions', header: '액션',
      render: (i) => i.status === 'OPEN'
        ? <Button size="sm" icon={<Icons.Check size={14} />} onClick={() => { setResolving(i); setResolveNote(''); }}>해결</Button>
        : null,
    },
  ];

  return (
    <div className="page">
      <PageHeader
        title={PAGE_TITLE}
        sub={PAGE_SUB}
        actions={<>
          <Button onClick={() => setBackfillOpen(true)}>체인 백필(마이그레이션)…</Button>
          <Button icon={<Icons.Download size={14} />} onClick={() => setShowReport(true)}>월간 보고서</Button>
          {/* 읽기 전용: overview 호출 시 서버가 전 tenant chain 을 검증한다. 진행률은 서버가 주지 않으므로 표시하지 않는다. */}
          <Button variant="primary" icon={<Icons.Hash size={14} />} onClick={reverify} disabled={verifying}>
            {verifying ? '검증 중…' : '지금 검증'}
          </Button>
        </>}
      />

      {chain.status === 'failed' && (
        <Alert
          tone="warn"
          title="다시 검증하지 못했습니다"
          actions={<Button size="sm" icon={<Icons.Refresh size={14} />} onClick={reverify} disabled={verifying}>다시 시도</Button>}
        >
          {`${describeError(chain.error).title}. 아래는 ${timeAgo(overview.verifiedAt)} 검증 결과입니다.`}
        </Alert>
      )}

      <section className="card" aria-label="전체 상태" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 20, padding: '20px 24px' }}>
        <span aria-hidden="true" style={{ width: 48, height: 48, borderRadius: 12, flex: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', background: tampered ? 'var(--bad-soft)' : 'var(--ok-soft)', color: tampered ? 'var(--bad)' : 'var(--ok)' }}>
          <Icons.Link size={24} />
        </span>
        <div className="col" style={{ gap: 2, flex: '1 1 320px', minWidth: 0 }}>
          <span className="t-over">전체 상태</span>
          <strong style={{ fontSize: 20, lineHeight: '28px' }}>{headline}</strong>
          <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{lastLine}</span>
        </div>
      </section>

      {tamperedRows.length > 0 && (
        <Alert tone="bad" title={`위변조 의심 — ${tamperedRows.length}개 tenant 모두 조사가 필요합니다`}>
          <ul aria-label="위변조 의심 tenant" style={{ listStyle: 'none', margin: '8px 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {tamperedRows.map((c) => {
              const hasOpen = c.tenantId !== null && openIncidentTenants.has(c.tenantId);
              return (
                <li key={c.tenantId ?? 'platform'} className="row" style={{ gap: 8 }}>
                  <span style={{ flex: '1 1 240px', minWidth: 0 }}>
                    <strong>{c.tenantName}</strong>
                    {' chain 에서 hash 불일치가 감지되었습니다.'}
                    {c.tamperedEntryId && ` 불일치 entry …${c.tamperedEntryId.slice(-8)}`}
                  </span>
                  <Button size="sm" onClick={() => openAudit(c.tenantId)} disabled={!c.tenantId}>
                    감사 로그 열기 <Icons.ChevronRight size={12} />
                  </Button>
                  {hasOpen ? (
                    <Badge tone="warn">Incident 처리 중</Badge>
                  ) : (
                    <Button
                      size="sm"
                      variant="danger-solid"
                      onClick={() => setCreateTarget(c)}
                      disabled={!c.tenantId}
                      title={c.tenantId ? undefined : '플랫폼 체인은 incident 대상이 아닙니다'}
                    >
                      Incident 생성
                    </Button>
                  )}
                </li>
              );
            })}
          </ul>
        </Alert>
      )}

      <div className="kpis">
        <KpiCard label="정상 / 전체" value={`${totals.tenantsIntact} / ${totals.tenantsTotal}`} />
        <KpiCard label="검증된 audit row" value={fmt(totals.verifiedRows)} />
        <KpiCard
          label="검증 방식"
          value={overview.nextRunAt === undefined ? '요청 시' : '자동'}
          hint={overview.nextRunAt === undefined ? '화면 진입 · 지금 검증' : `다음 검증 ${kstDateTime(overview.nextRunAt)} KST`}
        />
        <KpiCard label="검증 소요" value={`${fmt(totals.verificationMs)}ms`} />
      </div>

      <Card>
        <CardHeader
          title="Tenant별 Chain 상태"
          cap={`${timeAgo(overview.verifiedAt)} 검증 기준${showStrip ? ` · 스트립은 최근 ${STRIP_RUNS}회 검증` : ''}`}
          actions={
            <Segmented<'all' | 'tampered'>
              ariaLabel="상태 필터"
              value={view}
              onChange={setView}
              options={[{ value: 'all', label: '전체' }, { value: 'tampered', label: '위변조만' }]}
            />
          }
        />
        <DataTable
          columns={chainColumns}
          rows={rows}
          rowKey={(c) => c.tenantId ?? 'platform'}
          empty={
            <p className="muted" style={{ margin: 0, padding: '24px 16px', textAlign: 'center', fontSize: 13 }}>
              {view === 'tampered' ? '위변조 의심 tenant 가 없습니다.' : '검증할 tenant 가 없습니다.'}
            </p>
          }
        />
      </Card>

      <Card>
        <CardHeader
          title="보안 Incident"
          cap="위변조 감지 시 등록되는 대응 incident"
          actions={openCount > 0 ? <Badge tone="warn">{`${statusLabel('OPEN')} ${openCount}`}</Badge> : undefined}
        />
        {incidentError && (
          <div className="card__body">
            <Alert
              tone="bad"
              title="Incident 목록을 불러오지 못했습니다."
              actions={<Button size="sm" icon={<Icons.Refresh size={14} />} onClick={() => void refreshIncidents()}>다시 시도</Button>}
            >
              {incidents.length > 0 ? '아래는 마지막으로 조회된 목록입니다.' : undefined}
            </Alert>
          </div>
        )}
        {!incidentError && incidents.length === 0 ? (
          <EmptyState
            compact
            illustration={illustrations.allClear}
            title="등록된 incident가 없습니다"
            description={tampered ? '위변조 의심 tenant 는 위 배너에서 Incident 를 생성하세요.' : '모든 chain 이 정상입니다.'}
          />
        ) : incidents.length > 0 ? (
          <DataTable columns={incidentColumns} rows={incidents} rowKey={(i) => i.id} />
        ) : null}
      </Card>

      <Dialog
        open={createTarget !== null}
        onClose={() => { if (!creating) setCreateTarget(null); }}
        title="Incident 생성"
        sub={createTarget?.tenantName}
        footer={<>
          <Button onClick={() => setCreateTarget(null)} disabled={creating}>취소</Button>
          <Button
            variant="danger-solid"
            onClick={() => { if (createTarget?.tenantId) void handleCreateIncident(createTarget.tenantId); }}
            disabled={creating || !createTarget?.tenantId}
          >
            {creating ? '생성 중…' : 'Incident 생성'}
          </Button>
        </>}
      >
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          {`${createTarget?.tenantName ?? '—'} 의 위변조 의심을 보안 incident 로 등록하고 대응 이력을 남깁니다.`}
        </p>
        <p className="hint" style={{ margin: 0 }}>
          생성 시점에 서버가 재검증해 위변조 entry 를 기록합니다. 이미 진행 중인 incident 가 있거나 재검증 결과 위변조가 아니면 생성되지 않습니다.
        </p>
      </Dialog>

      <Dialog
        open={resolving !== null}
        onClose={() => { if (!resolveBusy) { setResolving(null); setResolveNote(''); } }}
        title="Incident 해결"
        sub={resolving ? `${resolving.tenantName} · ${resolving.type}` : undefined}
        footer={<>
          <Button onClick={() => { setResolving(null); setResolveNote(''); }} disabled={resolveBusy}>취소</Button>
          <Button
            variant="primary"
            onClick={() => void handleResolveIncident()}
            disabled={resolveBusy || resolveNote.trim().length === 0}
          >
            {resolveBusy ? '처리 중…' : '해결로 표시'}
          </Button>
        </>}
      >
        <Field label="해결 메모" htmlFor="resolve-note" required help="필수 · 감사 로그에 함께 기록됩니다.">
          <textarea
            id="resolve-note"
            className="input"
            rows={3}
            maxLength={1024}
            aria-required="true"
            value={resolveNote}
            onChange={(e) => setResolveNote(e.target.value)}
            placeholder="원인 · 조치 내용"
          />
        </Field>
      </Dialog>

      <Dialog
        open={backfillOpen}
        onClose={() => { if (!backfilling) setBackfillOpen(false); }}
        role="alertdialog"
        title="체인 백필을 실행하시겠습니까?"
        initialFocusRef={backfillCancelRef}
        footer={<>
          <button ref={backfillCancelRef} type="button" className="btn" onClick={() => setBackfillOpen(false)} disabled={backfilling}>취소</button>
          <Button variant="danger-solid" onClick={() => void handleBackfill()} disabled={backfilling}>
            {backfilling ? '백필 중…' : '백필 실행'}
          </Button>
        </>}
      >
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          백필은 검증이 아닙니다. 이전 행과 링크가 맞지 않는 모든 행의 hash 를 다시 계산해 덮어씁니다. 그 뒤의 체인도 모두 다시 연결합니다.
        </p>
        <Alert tone="bad">
          행이 삭제된 체인에 실행하면 빈자리가 다시 이어져, 삭제 흔적(위변조 증거)이 사라집니다. 위변조가 감지된 상태라면 먼저 Incident 를 생성해 조사하세요.
        </Alert>
        <p className="hint" style={{ margin: 0 }}>hash 가 비어 있는 기존 데이터를 체인에 편입하는 마이그레이션 용도로만 사용하세요.</p>
      </Dialog>

      <MonthlyReportDialog open={showReport} onClose={() => setShowReport(false)} overview={overview} />
    </div>
  );
}
