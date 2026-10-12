// admin-ui/src/pages/ActivityPage.tsx
import { useState, useEffect, useRef, useMemo, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { activityApi } from '@/api/activity';
import { downloadCsv } from '@/lib/csvExport';
import { kstDateTime, kstTime, kstToday, utcIso, groupByDay } from '@/lib/kst';
import { errorToast } from '@/lib/errorToast';
import type { ActivityView, ActivityCategory, ActivityDetailView } from '@/api/types';
import { adaptFeedItems, type RecentActivityEvent } from './tenant/recentActivityAdapter';
import { actionLabel, eventSentence } from './activityLabels';
import { buildDiffRows, arrayChange, type DiffRow } from './activityDiff';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { illustrations } from '@/assets/illustrations';
import {
  Alert, Button, CopyBtn, EmptyState, IconButton, KpiCard, LoadError, PageHeader, RemovableChip, Segmented, Skeleton, useToast,
} from '@/ui';

// ── Helpers ──────────────────────────────────────────────────────────────────

function fmt(n: number | null | undefined): string {
  if (n == null) return '—';
  return n.toLocaleString();
}

function parsePayload(raw: string): Record<string, unknown> | null {
  try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
}

function fmtVal(v: unknown): string {
  if (v === undefined || v === null) return '—';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

// ── Adapter: server ActivityView → display shape ──────────────────────────────
// Server feed items use { action, actorEmail, category, createdAt, tenantSlug }
// Design expects  { type, actorType, actorId, ts, tenantName, tenantSlug }

type DisplayEvent = RecentActivityEvent;

function adaptServerView(view: ActivityView): {
  events: DisplayEvent[];
  kpi: { events24h: number; ops24h: number; security24h: number; p95Ms: number | null };
  topTenants: { tenantId: string; tenantName: string; tenantSlug: string; count: number }[];
} {
  const events = adaptFeedItems(view);

  const topTenants = view.top5.map((t) => ({
    tenantId: t.tenantId,
    tenantName: t.slug,
    tenantSlug: t.slug,
    count: t.count,
  }));

  return { events, kpi: view.kpi, topTenants };
}

// 픽스처의 가짜 p95 를 보이지 않도록 0/null 로 시작한다(첫 응답 전에는 로딩 화면이라 노출되지 않음).
const EMPTY_KPI: { events24h: number; ops24h: number; security24h: number; p95Ms: number | null } = {
  events24h: 0, ops24h: 0, security24h: 0, p95Ms: null,
};

// ── Event 분류 → 색상 ─────────────────────────────────────────────────────────
// 서버가 분류한 category(ops/security/system)를 1차 기준으로, 이름이 *_FAILED/
// *_FAIL 인 실패 이벤트는 보안 실패로 격상해 한눈에 빨갛게 보이도록 한다.
// V2 토큰: danger=--bad(보안 실패), warning=--warn-dot(운영 변경), info=--brand-700(인증), teal=--faint(시스템).

type EventTone = 'danger' | 'warning' | 'info' | 'teal';

const FAILURE_TYPES = new Set([
  'SIGNATURE_COUNTER_REGRESSION',
  'ATTESTATION_TRUST_FAILED',
  'ADMIN_LOGIN_FAILED',
]);

const AUTH_TYPES = new Set([
  'ADMIN_LOGIN',
]);

function eventTone(type: string, category: string): EventTone {
  // 실패(이름 기준) 또는 보안 카테고리 → 빨강
  if (FAILURE_TYPES.has(type) || /_(FAILED|FAIL|REGRESSION)$/.test(type) || category === 'security') {
    return 'danger';
  }
  // 인증 성공 → 파랑
  if (AUTH_TYPES.has(type)) return 'info';
  // 운영 액션(테넌트/키 mutation) → 주황
  if (category === 'ops') return 'warning';
  // 시스템 자동(MDS sync, retention purge 등) → 회색
  return 'teal';
}

const TONE_VAR: Record<EventTone, string> = {
  danger: 'var(--bad)',
  warning: 'var(--warn-dot)',
  info: 'var(--brand-700)',
  teal: 'var(--faint)',
};

const LEGEND: { tone: EventTone; label: string }[] = [
  { tone: 'danger', label: '보안 실패' },
  { tone: 'warning', label: '운영 변경' },
  { tone: 'info', label: '인증' },
  { tone: 'teal', label: '시스템' },
];

const CATEGORY_LABEL: Record<ActivityCategory, string> = { all: '전체', ops: '운영', security: '보안' };

const REMOVED_TAG = { background: 'var(--bad-soft)', color: 'var(--bad-ink)' };
const ADDED_TAG = { background: 'var(--ok-soft)', color: 'var(--ok-ink)' };

// ── Detail panel pieces ───────────────────────────────────────────────────────

function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <div className="stack-1" style={{ gap: 6 }} role="group" aria-label={title}>
      <div className="row">
        <span className="t-over">{title}</span>
        {action && <><div className="spacer" />{action}</>}
      </div>
      {children}
    </div>
  );
}

function DiffLine({ row }: { row: DiffRow }) {
  const arr = arrayChange(row.from, row.to);
  return (
    <div className="row" style={{ gap: 8, fontSize: 12 }}>
      <span className="mono truncate" style={{ width: 84, color: 'var(--mute)' }} title={row.field}>{row.field}</span>
      {arr ? (
        <>
          {arr.removed.map((x) => <span key={`-${x}`} className="tag" style={REMOVED_TAG}>{`− ${x}`}</span>)}
          {arr.added.map((x) => <span key={`+${x}`} className="tag" style={ADDED_TAG}>{`+ ${x}`}</span>)}
        </>
      ) : (
        <>
          <span className="tag" style={{ ...REMOVED_TAG, textDecoration: 'line-through' }}>{fmtVal(row.from)}</span>
          <span style={{ color: 'var(--mute)', display: 'inline-flex' }}><Icons.ChevronRight size={14} /></span>
          <span className="tag" style={ADDED_TAG}>{fmtVal(row.to)}</span>
        </>
      )}
      <span className="sr-only">{`${row.field}: ${fmtVal(row.from)} 에서 ${fmtVal(row.to)} 로 변경`}</span>
    </div>
  );
}

function DetailPanel({
  detail, loading, error, onRetry, onClose, onOpenTenant,
}: { detail: ActivityDetailView | null; loading: boolean; error: unknown; onRetry: () => void; onClose: () => void; onOpenTenant: (id: string | null) => void }) {
  const payload = detail ? parsePayload(detail.payload) : null;
  const json = payload ? JSON.stringify(payload, null, 2) : (detail?.payload ?? '');
  const rows = detail ? buildDiffRows(detail) : [];
  return (
    <section className="card" aria-label="이벤트 상세">
      <div className="card__head">
        <h2 className="card__title">상세</h2>
        {detail && <span className="tag" title={actionLabel(detail.action)}>{detail.action}</span>}
        <div className="spacer" />
        <IconButton label="닫기" icon={<Icons.X size={16} />} onClick={onClose} />
      </div>
      <div className="card__body stack-4" style={{ gap: 18 }}>
        {loading && <Skeleton rows={4} label="상세 불러오는 중" />}
        {!loading && error != null && <LoadError compact what="이벤트 상세" error={error} onRetry={onRetry} />}
        {!loading && detail && (
          <>
            <Section title="언제">
              <span style={{ fontSize: 13 }}>{kstDateTime(detail.createdAt)} <span className="muted" style={{ fontSize: 12 }}>KST</span></span>
              <span className="mono muted" style={{ fontSize: 12 }}>{`${utcIso(detail.createdAt)} UTC`}</span>
            </Section>
            <Section title="누가">
              <span className="row" style={{ gap: 8, fontSize: 13 }}>
                <span className="avatar avatar--sm" aria-hidden="true">{(detail.actorEmail || 'S').slice(0, 1).toUpperCase()}</span>
                <span>{detail.actorEmail || 'system'}</span>
              </span>
              {detail.actorIp !== undefined && <span className="mono muted" style={{ fontSize: 12 }}>{`IP ${detail.actorIp}`}</span>}
            </Section>
            <Section title="테넌트 · 대상">
              <span className="row" style={{ gap: 4, fontSize: 13 }}>
                {detail.tenantId
                  ? <Button variant="ghost" size="xs" onClick={() => onOpenTenant(detail.tenantId)}>{detail.tenantSlug ?? detail.tenantId}</Button>
                  : <span>플랫폼</span>}
                <span>{`· ${detail.targetType ?? '—'}`}</span>
                {detail.targetId && <span className="mono muted" style={{ fontSize: 12 }} title={detail.targetId}>{`…${detail.targetId.slice(-8)}`}</span>}
              </span>
            </Section>
            {rows.length > 0 && (
              <Section title="어떻게 바뀜">
                {rows.map((r) => <DiffLine key={r.field} row={r} />)}
              </Section>
            )}
            <Section title="원본 JSON" action={<CopyBtn value={json} label="복사" />}>
              <pre className="mono" style={{ margin: 0, padding: 12, borderRadius: 8, background: 'var(--canvas)', border: '1px solid var(--line-soft)', fontSize: 11, lineHeight: '17px', whiteSpace: 'pre-wrap', wordBreak: 'break-all', color: 'var(--ink-2)', maxHeight: 320, overflow: 'auto' }}>{json}</pre>
            </Section>
          </>
        )}
      </div>
    </section>
  );
}

// ── Feed row ──────────────────────────────────────────────────────────────────

function FeedRow({ e, selected, onSelect, onOpenTenant }: {
  e: DisplayEvent; selected: boolean; onSelect: (id: string) => void; onOpenTenant: (id: string | null) => void;
}) {
  return (
    <div
      className="row"
      aria-current={selected ? 'true' : undefined}
      style={{
        flexWrap: 'nowrap', alignItems: 'flex-start', gap: 12, padding: '12px 20px',
        borderBottom: '1px solid var(--line-soft)',
        background: selected ? 'var(--brand-50)' : 'var(--surface)',
        boxShadow: selected ? 'inset 3px 0 0 var(--brand-700)' : 'none',
      }}
    >
      <span className="mono muted" style={{ width: 44, flex: 'none', paddingTop: 2, fontSize: 12 }}>{kstTime(e.ts)}</span>
      <span className="dot" aria-hidden="true" style={{ marginTop: 7, background: TONE_VAR[eventTone(e.type, e.category)] }} />
      <div className="col" style={{ gap: 4, flex: '1 1 0', minWidth: 0 }}>
        <div className="row" style={{ gap: 8 }}>
          <span className="tag" title={actionLabel(e.type)}>{e.type}</span>
          {e.tenantId
            ? <Button variant="ghost" size="xs" onClick={() => onOpenTenant(e.tenantId)}>{e.tenantName}</Button>
            : <span className="muted" style={{ fontSize: 13 }}>플랫폼</span>}
        </div>
        <span style={{ fontSize: 13, color: 'var(--ink)' }}>
          {eventSentence({
            action: e.type,
            actorEmail: e.actorId ?? '',
            tenantSlug: e.tenantSlug,
            targetType: null,
            targetId: e.subjectId,
          })}
        </span>
      </div>
      <IconButton label="상세 열기" icon={<Icons.ChevronRight size={16} />} onClick={() => onSelect(e.id)} />
    </div>
  );
}

// ── Live 연결 상태 ────────────────────────────────────────────────────────────

/** 폴링 상태. 실패하면 초록 "실시간" 대신 회색 점 + 경고색 "연결 끊김" 과 마지막 갱신 경과 시간을 보인다. */
function LiveStatus({ disconnected, lastOkAt, now }: { disconnected: boolean; lastOkAt: number | null; now: number }) {
  const ago = lastOkAt === null ? '' : ` · ${Math.max(0, Math.floor((now - lastOkAt) / 1000))}초 전 갱신`;
  return (
    <>
      <span className="row" style={{ gap: 6, fontSize: 12, color: disconnected ? 'var(--warn)' : 'var(--mute)', fontWeight: disconnected ? 600 : undefined }}>
        <span className="dot" aria-hidden="true" style={{ background: disconnected ? 'var(--faint)' : 'var(--ok)' }} />
        {disconnected ? `연결 끊김${ago}` : `실시간${ago}`}
      </span>
      {/* 초 단위 카운터는 live 영역 밖. 연결이 복구될 때만 알린다(끊김은 아래 경고 배너가 알린다). */}
      <span className="sr-only" role="status">{disconnected ? '' : '실시간 연결됨'}</span>
    </>
  );
}

// ── ActivityPage ──────────────────────────────────────────────────────────────

const POLL_MS = 5000;

export default function ActivityPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();

  // 카테고리·tenant 필터는 URL(category·tenantId)이 단일 출처 — 새로고침·링크 공유에도 유지된다.
  const rawCategory = searchParams.get('category');
  const categoryFilter: ActivityCategory = rawCategory === 'ops' || rawCategory === 'security' ? rawCategory : 'all';
  const selectedTenant = searchParams.get('tenantId') || undefined;

  // null = 아직 서버 응답 없음
  const [events, setEvents] = useState<DisplayEvent[] | null>(null);
  const [kpi, setKpi] = useState(EMPTY_KPI);
  const [topTenants, setTopTenants] = useState<{ tenantId: string; tenantName: string; tenantSlug: string; count: number }[]>([]);
  // 데이터를 한 번도 받지 못한 실패 → loadError(LoadError). 받은 뒤의 실패 → disconnected(목록 유지).
  const [loadError, setLoadError] = useState<unknown>(null);
  const [disconnected, setDisconnected] = useState(false);
  const [lastOkAt, setLastOkAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [retrying, setRetrying] = useState(false);

  const [detail, setDetail] = useState<ActivityDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [visibleCount, setVisibleCount] = useState(24);

  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [actionQuery, setActionQuery] = useState('');

  function setFilterParam(key: 'category' | 'tenantId', value: string | null) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value); else next.delete(key);
      return next;
    }, { replace: true });
  }
  function setCategoryFilter(c: ActivityCategory) { setFilterParam('category', c === 'all' ? null : c); }
  function setSelectedTenant(id: string | undefined) { setFilterParam('tenantId', id ?? null); }

  // Load tenant list once for the filter dropdown
  useEffect(() => {
    tenantsApi.list().then(setTenants).catch(() => setTenants([]));
  }, []);

  // "N초 전 갱신" 을 1초마다 다시 그린다.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  // 5-second polling. 필터(category/tenant)가 바뀔 때마다 세대를 올리고, 이전 세대의 늦은 응답은 버린다.
  const queryGenRef = useRef(0);
  // 가장 나중에 시작한 요청만 상태를 바꾼다(폴링·수동 새로고침 공통). 폴링은 진행 중이면 겹쳐 시작하지 않는다.
  const reqSeqRef = useRef(0);
  const pollInFlightRef = useRef(false);
  const manualInFlightRef = useRef(0);
  // 필터가 바뀐 뒤 첫 응답은 이전 필터의 목록을 병합하지 않고 통째로 교체한다.
  const replaceNextRef = useRef(true);
  // Track whether we've had at least one successful server response
  const hasServerDataRef = useRef(false);

  function applyView(view: ActivityView) {
    const mode = replaceNextRef.current ? 'replace' : 'merge';
    replaceNextRef.current = false;
    const adapted = adaptServerView(view);
    hasServerDataRef.current = true;
    const t = Date.now();
    setLoadError(null);
    setDisconnected(false);
    setLastOkAt(t);
    setNow(t);
    // merge: 폴링은 최신 한 페이지만 받으므로, 아직 없는 행만 앞에 붙여 '더 보기'로 불러온 행을 유지한다.
    setEvents((prev) => {
      if (mode === 'replace' || prev === null) return adapted.events;
      const have = new Set(prev.map((e) => e.id));
      const fresh = adapted.events.filter((e) => !have.has(e.id));
      return fresh.length === 0 ? prev : [...fresh, ...prev];
    });
    setKpi((prev) => ({
      ...prev,
      ...adapted.kpi,
      // p95Ms: server may return null; keep previous value as fallback
      p95Ms: adapted.kpi.p95Ms ?? prev.p95Ms,
    }));
    setTopTenants(adapted.topTenants);
  }

  function onFetchFailed(e: unknown) {
    if (hasServerDataRef.current) setDisconnected(true); // 이미 보이는 목록은 지우지 않는다
    else setLoadError(e);
  }

  useEffect(() => {
    let cancelled = false;
    const gen = ++queryGenRef.current;
    replaceNextRef.current = true;
    pollInFlightRef.current = false;
    // 조회 조건이 바뀌면 이전 조건의 이벤트·KPI 를 버린다. 새 조건의 첫 조회가 실패하면
    // "연결 끊김"(이전 목록 유지)이 아니라 전체 LoadError(KPI "—", 내보내기 비활성)로 보인다.
    hasServerDataRef.current = false;
    setEvents(null);
    setKpi(EMPTY_KPI);
    setTopTenants([]);
    setLoadError(null);
    setDisconnected(false);
    setLastOkAt(null);

    async function fetchOnce() {
      if (pollInFlightRef.current) return;
      pollInFlightRef.current = true;
      const seq = ++reqSeqRef.current;
      try {
        const view = await activityApi.fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant);
        if (cancelled || gen !== queryGenRef.current || seq !== reqSeqRef.current) return;
        applyView(view);
      } catch (e) {
        if (cancelled || gen !== queryGenRef.current || seq !== reqSeqRef.current) return;
        onFetchFailed(e);
      } finally {
        if (gen === queryGenRef.current) pollInFlightRef.current = false;
      }
    }

    // Reset visible window when the query (category/tenant) changes so the
    // user sees fresh first-page data, not a stale large slice.
    setVisibleCount(24);

    void fetchOnce();
    const id = setInterval(() => void fetchOnce(), POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [categoryFilter, selectedTenant]);

  const displayEvents = useMemo(() => events ?? [], [events]);
  const filtered = useMemo(
    () =>
      displayEvents.filter((e) => {
        if (!actionQuery.trim()) return true;
        const q = actionQuery.trim().toUpperCase();
        return e.type.toUpperCase().includes(q) || actionLabel(e.type).includes(actionQuery.trim());
      }),
    [displayEvents, actionQuery],
  );

  // 마지막으로 연 상세 요청만 반영한다(A→B 순서로 열고 A 가 늦게 오거나, 로딩 중 닫은 경우).
  const detailReqRef = useRef(0);

  function openDetail(id: string) {
    const req = ++detailReqRef.current;
    setSelectedId(id);
    setDetailLoading(true);
    setDetail(null);
    setDetailError(null);
    activityApi
      .fetchDetail(id)
      .then((d) => { if (req === detailReqRef.current) setDetail(d); })
      .catch((e: unknown) => { if (req === detailReqRef.current) setDetailError(e); })
      .finally(() => { if (req === detailReqRef.current) setDetailLoading(false); });
  }

  function closeDetail() {
    detailReqRef.current++;
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
    setSelectedId(null);
  }

  function handleOpenTenant(tenantId: string | null) {
    if (!tenantId) return;
    navigate(`/tenants/${tenantId}`);
  }

  // 새로고침 · "지금 다시 연결" · LoadError "다시 시도" 공통. 결과는 상태 표시로 알린다(토스트 중복 없음).
  function handleRefresh() {
    const gen = queryGenRef.current;
    const seq = ++reqSeqRef.current;
    const latest = () => gen === queryGenRef.current && seq === reqSeqRef.current;
    manualInFlightRef.current++;
    setRetrying(true);
    activityApi
      .fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant)
      .then((view) => { if (latest()) applyView(view); })
      .catch((e: unknown) => { if (latest()) onFetchFailed(e); })
      // 완료 표시는 응답이 최신인지와 무관하게 이 새로고침 자신의 종료로 정한다(추월당해도 버튼이 잠기지 않게).
      .finally(() => { manualInFlightRef.current--; if (manualInFlightRef.current === 0) setRetrying(false); });
  }

  function handleExport() {
    if (!events || events.length === 0) {
      toast({ kind: 'warn', title: '내보낼 데이터 없음' });
      return;
    }
    downloadCsv(
      `activity-${kstToday()}.csv`,
      ['timestamp', 'tenant', 'type', 'category', 'actor', 'subject'],
      events.map((e) => [e.ts, e.tenantSlug ?? e.tenantId ?? '', e.type, e.category, e.actorId ?? '', e.subjectId]),
    );
  }

  async function handleLoadMore() {
    if (!events || events.length === 0) return;
    const oldestEvent = events[events.length - 1];
    const oldest = oldestEvent.ts;
    const gen = queryGenRef.current;
    try {
      const more = await activityApi.fetch(
        null,
        categoryFilter === 'all' ? undefined : categoryFilter,
        oldest,
        selectedTenant,
        // F07: send the oldest visible row's id as the tuple-cursor
        // partner to `before` so a row sharing the exact same
        // microsecond timestamp as `oldest` is not dropped.
        oldestEvent.id,
      );
      if (gen !== queryGenRef.current) return;
      const adapted = adaptServerView(more);
      setEvents((prev) => {
        const base = prev ?? [];
        const have = new Set(base.map((e) => e.id));
        return [...base, ...adapted.events.filter((e) => !have.has(e.id))];
      });
      // Expand the visible window so newly-loaded rows actually appear in the feed
      setVisibleCount((n) => n + Math.max(adapted.events.length, 24));
    } catch (err: unknown) {
      toast(errorToast(err, '이전 기록을 불러오지 못했습니다'));
    }
  }

  function clearFilters() {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('category');
      next.delete('tenantId');
      return next;
    }, { replace: true });
    setActionQuery('');
  }

  const header = (
    <PageHeader
      title="Activity"
      sub="전체 tenant의 패스키 등록·인증 · 운영 변경 · 보안 이벤트를 모아 봅니다."
      actions={<>
        {events !== null && <LiveStatus disconnected={disconnected} lastOkAt={lastOkAt} now={now} />}
        <Button icon={<Icons.Refresh size={14} />} onClick={handleRefresh} disabled={events === null || retrying}>새로고침</Button>
        <Button icon={<Icons.Download size={14} />} onClick={handleExport} disabled={events === null}>내보내기</Button>
      </>}
    />
  );

  const selectedTenantSlug = selectedTenant
    ? (tenants.find((t) => t.id === selectedTenant)?.slug ?? topTenants.find((t) => t.tenantId === selectedTenant)?.tenantSlug ?? selectedTenant)
    : null;
  const hasApplied = categoryFilter !== 'all' || !!selectedTenant || actionQuery.trim() !== '';
  // 필터는 로딩·실패 화면에서도 보인다 — 바꾼 조건의 조회가 실패하면 되돌릴 수 있어야 한다.
  const filters = (
    <div className="card" style={{ padding: '14px 20px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className="row" style={{ gap: 12 }}>
        <Segmented<ActivityCategory>
          ariaLabel="카테고리"
          value={categoryFilter}
          onChange={setCategoryFilter}
          options={[{ value: 'all', label: '전체' }, { value: 'ops', label: '운영' }, { value: 'security', label: '보안' }]}
        />
        <select
          className="input"
          aria-label="테넌트"
          style={{ width: 200 }}
          value={selectedTenant ?? ''}
          onChange={(ev) => setSelectedTenant(ev.target.value || undefined)}
        >
          <option value="">테넌트 · 전체</option>
          {tenants.map((t) => (
            <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
          ))}
        </select>
        <div className="search" style={{ flex: '1 1 220px' }}>
          <Icons.Search size={15} />
          <input className="input" aria-label="액션 검색" placeholder="액션 검색…" value={actionQuery} onChange={(ev) => setActionQuery(ev.target.value)} />
        </div>
      </div>
      {hasApplied && (
        <div className="row" style={{ gap: 8 }}>
          <span className="muted" style={{ fontSize: 12 }}>적용된 필터</span>
          {categoryFilter !== 'all' && (
            <RemovableChip removeLabel="카테고리 필터 제거" onRemove={() => setCategoryFilter('all')}>{`카테고리: ${CATEGORY_LABEL[categoryFilter]}`}</RemovableChip>
          )}
          {selectedTenantSlug && (
            <RemovableChip removeLabel="테넌트 필터 제거" onRemove={() => setSelectedTenant(undefined)}>{`테넌트: ${selectedTenantSlug}`}</RemovableChip>
          )}
          {actionQuery.trim() !== '' && (
            <RemovableChip removeLabel="액션 검색 제거" onRemove={() => setActionQuery('')}>{`액션: ${actionQuery.trim()}`}</RemovableChip>
          )}
          <Button variant="ghost" size="sm" onClick={clearFilters}>모두 지우기</Button>
        </div>
      )}
    </div>
  );

  // 응답 전·실패 시 KPI 는 0 이 아니라 "—".
  const kpiValue = (n: number) => (events === null ? '—' : fmt(n));
  const kpis = (
    <div className={kpi.p95Ms != null ? 'kpis' : 'kpis kpis--3'}>
      <KpiCard label="활동 (24h)" value={kpiValue(kpi.events24h)} />
      <KpiCard label="운영 액션 (24h)" value={kpiValue(kpi.ops24h)} />
      <KpiCard
        label="보안 이벤트 (24h)"
        value={<span style={{ color: events !== null && kpi.security24h > 0 ? 'var(--bad)' : undefined }}>{kpiValue(kpi.security24h)}</span>}
      />
      {/* §6: 서버가 p95Ms 를 주지 않으면(현재 항상 null) KPI 를 그리지 않는다. */}
      {kpi.p95Ms != null && <KpiCard label="평균 응답" value={`${kpi.p95Ms}ms`} hint={`p95 ${kpi.p95Ms}ms`} />}
    </div>
  );

  // 첫 응답 전: 로딩(Skeleton) 또는 첫 로드 실패(LoadError). 픽스처를 실데이터처럼 보이지 않는다.
  if (events === null) {
    return (
      <div className="page">
        {header}
        {kpis}
        {filters}
        <div className="card">
          {loadError != null
            ? <LoadError what="활동" error={loadError} onRetry={handleRefresh} retrying={retrying} />
            : <Skeleton rows={6} />}
        </div>
      </div>
    );
  }

  const groups = groupByDay(filtered.slice(0, visibleCount), (e) => e.ts, new Date());
  const maxTop = Math.max(1, ...topTenants.map((t) => t.count));
  return (
    <div className="page">
      {header}

      {disconnected && lastOkAt !== null && (
        <Alert
          tone="warn"
          title="새 이벤트를 받지 못하고 있습니다"
          actions={<Button size="sm" icon={<Icons.Refresh size={14} />} onClick={handleRefresh} disabled={retrying}>지금 다시 연결</Button>}
        >
          {`아래 목록은 ${kstDateTime(new Date(lastOkAt).toISOString()).slice(11)} 기준입니다. ${POLL_MS / 1000}초마다 자동으로 다시 연결합니다.`}
        </Alert>
      )}

      {kpis}

      {filters}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 20, alignItems: 'flex-start' }}>
        <section className="card" style={{ flex: '999 1 460px', minWidth: 0 }}>
          <div className="card__head">
            <h2 className="card__title">최근 이벤트</h2>
            <div className="spacer" />
            <span className="row muted" style={{ gap: 12, fontSize: 12 }}>
              {LEGEND.map((l) => (
                <span key={l.tone} className="row" style={{ gap: 5 }}>
                  <span className="dot" aria-hidden="true" style={{ background: TONE_VAR[l.tone] }} />{l.label}
                </span>
              ))}
            </span>
          </div>
          {groups.length === 0 ? (
            <EmptyState
              compact
              illustration={illustrations.noResults}
              title="조건에 맞는 이벤트가 없습니다"
              description="필터를 바꾸거나 이전 24시간을 더 불러오세요."
            />
          ) : (
            groups.map((g, gi) => (
              <div key={`${g.label}-${gi}`} role="group" aria-label={g.label}>
                <div className="t-over" style={{ padding: '8px 20px', background: 'var(--canvas)', borderBottom: '1px solid var(--line-soft)' }}>{g.label}</div>
                {g.items.map((e) => (
                  <FeedRow key={e.id} e={e} selected={selectedId === e.id} onSelect={openDetail} onOpenTenant={handleOpenTenant} />
                ))}
              </div>
            ))
          )}
          <div className="card__foot" style={{ justifyContent: 'center' }}>
            <Button onClick={() => void handleLoadMore()}>이전 24시간 더 보기</Button>
          </div>
        </section>

        <aside style={{ flex: '1 1 320px', maxWidth: '100%', display: 'flex', flexDirection: 'column', gap: 20 }}>
          {(detail || detailLoading || detailError != null) && (
            <DetailPanel
              detail={detail}
              loading={detailLoading}
              error={detailError}
              onRetry={() => { if (selectedId) openDetail(selectedId); }}
              onClose={closeDetail}
              onOpenTenant={handleOpenTenant}
            />
          )}
          <section className="card">
            <div className="card__head">
              <h2 className="card__title">활발한 Tenant</h2>
              <span className="muted" style={{ fontSize: 12 }}>24h</span>
            </div>
            {topTenants.length === 0 ? (
              <p className="muted" style={{ margin: 0, padding: '16px 20px', fontSize: 13 }}>최근 24시간 이벤트가 없습니다.</p>
            ) : topTenants.map((t) => (
              <button
                key={t.tenantId}
                type="button"
                className="row"
                aria-pressed={selectedTenant === t.tenantId}
                onClick={() => setSelectedTenant(t.tenantId)}
                style={{ flexWrap: 'nowrap', gap: 10, padding: '10px 20px', width: '100%', border: 0, borderBottom: '1px solid var(--line-soft)', background: selectedTenant === t.tenantId ? 'var(--brand-50)' : 'transparent', textAlign: 'left', cursor: 'pointer', color: 'var(--ink)', font: 'inherit' }}
              >
                <span className="avatar avatar--sq" aria-hidden="true" style={{ width: 28, height: 28, fontSize: 12 }}>{t.tenantName.slice(0, 1).toUpperCase()}</span>
                <span className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                  <span className="truncate" style={{ fontSize: 13, fontWeight: 500 }}>{t.tenantName}</span>
                  <span className="mono muted" style={{ fontSize: 12 }}>{t.tenantSlug}</span>
                </span>
                <span aria-hidden="true" style={{ width: 64, height: 6, borderRadius: 3, background: 'var(--line-soft)', flex: 'none' }}>
                  <span style={{ display: 'block', width: `${Math.round((t.count / maxTop) * 100)}%`, height: 6, borderRadius: 3, background: 'var(--brand-500)' }} />
                </span>
                <span style={{ fontSize: 13, fontWeight: 600, width: 40, textAlign: 'right' }}>{fmt(t.count)}</span>
              </button>
            ))}
          </section>
        </aside>
      </div>
    </div>
  );
}
