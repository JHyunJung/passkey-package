import { useState, useEffect, useRef, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { activityApi } from '@/api/activity';
import { activityFixture } from '@/fixtures/activity';
import { useToast } from '@/shell/ToastHost';
import { downloadCsv } from '@/lib/csvExport';
import type { ActivityView, ActivityCategory, ActivityDetailView } from '@/api/types';
import { adaptFeedItems, type RecentActivityEvent } from './tenant/recentActivityAdapter';
import { actionLabel, eventSentence } from './activityLabels';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';

// ── Helpers ──────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}초 전`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  return `${Math.floor(h / 24)}일 전`;
}

function fmt(n: number | null | undefined): string {
  if (n == null) return '—';
  return n.toLocaleString();
}

function parsePayload(raw: string): Record<string, unknown> | null {
  try { return JSON.parse(raw) as Record<string, unknown>; } catch { return null; }
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

// ── Event 분류 → 색상 ─────────────────────────────────────────────────────────
// 서버가 분류한 category(ops/security/system)를 1차 기준으로, 이름이 *_FAILED/
// *_FAIL 인 실패 이벤트는 보안 실패로 격상해 한눈에 빨갛게 보이도록 한다.
// 토큰: danger=보안실패, warning=운영변경, info=인증성공, teal=시스템자동.

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
  // 시스템 자동(MDS sync, retention purge 등) → 청록
  return 'teal';
}

const TONE_VAR: Record<EventTone, string> = {
  danger: 'var(--danger)',
  warning: 'var(--warning)',
  info: 'var(--info)',
  teal: 'var(--teal)',
};

const TONE_BADGE: Record<EventTone, string> = {
  danger: 'badge badge--danger',
  warning: 'badge badge--warning',
  info: 'badge badge--info',
  teal: 'badge badge--teal',
};

// ── EventDot ─────────────────────────────────────────────────────────────────

function EventDot({ type, category }: { type: string; category: string }) {
  return (
    <div
      style={{
        width: 7,
        height: 7,
        borderRadius: '50%',
        background: TONE_VAR[eventTone(type, category)],
        flex: 'none',
      }}
    />
  );
}

// ── EventTypeBadge ────────────────────────────────────────────────────────────

function EventTypeBadge({ type, category }: { type: string; category: string }) {
  return <span className={TONE_BADGE[eventTone(type, category)]} style={{ fontSize: 10 }}>{actionLabel(type)}</span>;
}

// ── LegendDot — 피드 색상 범례 ─────────────────────────────────────────────────

function LegendDot({ tone, label }: { tone: EventTone; label: string }) {
  return (
    <span className="row" style={{ gap: 5, fontSize: 11, color: 'var(--text-mute)' }}>
      <span style={{ width: 7, height: 7, borderRadius: '50%', background: TONE_VAR[tone], flex: 'none' }} />
      {label}
    </span>
  );
}

// ── ChipTab ───────────────────────────────────────────────────────────────────

function ChipTab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={active ? 'badge badge--accent' : 'badge'}
      style={{
        cursor: 'pointer',
        padding: '3px 9px',
        fontSize: 11,
        border: active ? '1px solid var(--accent)' : '1px solid var(--border)',
      }}
    >
      {children}
    </button>
  );
}

// ── MetricCard ────────────────────────────────────────────────────────────────

function MetricCard({
  label,
  value,
  sub,
}: {
  label: string;
  value: string | number;
  sub?: string;
}) {
  return (
    <div className="card" style={{ padding: '16px 20px' }}>
      <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 700, lineHeight: 1 }}>{fmt(typeof value === 'number' ? value : undefined) !== '—' ? value : value}</div>
      {sub && <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>{sub}</div>}
    </div>
  );
}

// ── DetailPanel ───────────────────────────────────────────────────────────────

function DetailPanel({
  detail, loading, onClose,
}: { detail: ActivityDetailView | null; loading: boolean; onClose: () => void }) {
  const payload = detail ? parsePayload(detail.payload) : null;
  const before = payload && typeof payload.before === 'object' && payload.before !== null ? payload.before as Record<string, unknown> : null;
  const after = payload && typeof payload.after === 'object' && payload.after !== null ? payload.after as Record<string, unknown> : null;
  return (
    <div className="card" style={{ padding: 14 }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 className="card__title">상세</h3>
        <button className="btn btn--ghost btn--xs" onClick={onClose}>닫기</button>
      </div>
      {loading && <div className="muted" style={{ fontSize: 12 }}>불러오는 중…</div>}
      {!loading && !detail && <div className="muted" style={{ fontSize: 12 }}>행을 클릭하면 상세가 표시됩니다.</div>}
      {!loading && detail && (
        <div style={{ fontSize: 12 }}>
          <div style={{ fontWeight: 600, marginBottom: 4 }}>{actionLabel(detail.action)}</div>
          <div className="muted" style={{ marginBottom: 8 }}>{new Date(detail.createdAt).toLocaleString()}</div>
          <div><b>누가</b> {detail.actorEmail || 'system'}</div>
          <div><b>테넌트</b> {detail.tenantSlug ?? '플랫폼'}</div>
          <div style={{ marginBottom: 8 }}><b>대상</b> {detail.targetType ?? '—'} {detail.targetId ?? ''}</div>
          <div className="label" style={{ marginBottom: 4 }}>어떻게 바뀜</div>
          {before && after ? (
            <pre style={{ margin: 0, padding: 10, background: 'var(--surface-3)', borderRadius: 8, fontSize: 11, fontFamily: 'var(--mono)', overflow: 'auto', color: 'var(--text)' }}>
{Object.keys({ ...before, ...after }).map((k) =>
  `- ${k}: ${JSON.stringify(before[k])}\n+ ${k}: ${JSON.stringify(after[k])}`).join('\n')}
            </pre>
          ) : (
            <pre style={{ margin: 0, padding: 10, background: 'var(--surface-3)', borderRadius: 8, fontSize: 11, fontFamily: 'var(--mono)', overflow: 'auto', color: 'var(--text)' }}>
{payload ? JSON.stringify(payload, null, 2) : detail.payload}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

// ── ActivityPage ──────────────────────────────────────────────────────────────

export default function ActivityPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams] = useSearchParams();

  // null = not yet loaded from server; undefined = server error (show empty state)
  const [events, setEvents] = useState<DisplayEvent[] | null>(null);
  const [kpi, setKpi] = useState(activityFixture.kpi);
  const [topTenants, setTopTenants] = useState(activityFixture.topTenants);
  const [serverError, setServerError] = useState(false);

  const [detail, setDetail] = useState<ActivityDetailView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [categoryFilter, setCategoryFilter] = useState<ActivityCategory>('all');
  const [visibleCount, setVisibleCount] = useState(24);

  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [actionQuery, setActionQuery] = useState('');
  const [selectedTenant, setSelectedTenant] = useState<string | undefined>(
    searchParams.get('tenantId') ?? undefined,
  );

  // Load tenant list once for the filter dropdown
  useEffect(() => {
    tenantsApi.list().then(setTenants).catch(() => setTenants([]));
  }, []);

  // 5-second polling
  const cancelledRef = useRef(false);
  // Track whether we've had at least one successful server response
  const hasServerDataRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;

    async function fetchOnce() {
      try {
        const view = await activityApi.fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant);
        if (cancelledRef.current) return;
        const adapted = adaptServerView(view);
        hasServerDataRef.current = true;
        setServerError(false);
        setEvents(adapted.events);
        setKpi((prev) => ({
          ...prev,
          ...adapted.kpi,
          // p95Ms: server may return null; keep previous value as fallback
          p95Ms: adapted.kpi.p95Ms ?? prev.p95Ms,
        }));
        setTopTenants(adapted.topTenants);
      } catch {
        if (cancelledRef.current) return;
        if (!hasServerDataRef.current) {
          // Never had server data: show error state rather than fixture
          setServerError(true);
        }
        // If we already had server data, keep it (transient network blip)
      }
    }

    // Reset visible window when the query (category/tenant) changes so the
    // user sees fresh first-page data, not a stale large slice.
    setVisibleCount(24);

    void fetchOnce();
    const id = setInterval(() => void fetchOnce(), 5000);
    return () => {
      cancelledRef.current = true;
      clearInterval(id);
    };
  }, [categoryFilter, selectedTenant]);

  // Filtered events for the feed panel
  // Use e.category (server classification) for ops/security filter;
  // type-name sets are only for dot/badge color on fixture events where
  // category is pre-computed from the same sets.
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

  function openDetail(id: string) {
    setDetailLoading(true);
    setDetail(null);
    activityApi
      .fetchDetail(id)
      .then((d) => setDetail(d))
      .catch(() => toast({ kind: 'err', title: '상세 로드 실패' }))
      .finally(() => setDetailLoading(false));
  }

  function handleOpenTenant(tenantId: string | null) {
    if (!tenantId) return;
    navigate(`/tenants/${tenantId}`);
  }

  function handleRefresh() {
    activityApi
      .fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, selectedTenant)
      .then((view) => {
        if (cancelledRef.current) return;
        const adapted = adaptServerView(view);
        hasServerDataRef.current = true;
        setServerError(false);
        setEvents(adapted.events);
        setKpi((prev) => ({
          ...prev,
          ...adapted.kpi,
          p95Ms: adapted.kpi.p95Ms ?? prev.p95Ms,
        }));
        setTopTenants(adapted.topTenants);
        toast({ kind: 'ok', title: '새로고침 완료' });
      })
      .catch(() => toast({ kind: 'warn', title: '새로고침 실패', message: '서버 응답 없음' }));
  }

  // Loading state: waiting for first server response
  if (events === null && !serverError) {
    return (
      <div className="page">
        <div style={{ padding: 40, color: 'var(--text-mute)' }}>Activity 데이터 로딩 중…</div>
      </div>
    );
  }

  // Error state: first fetch failed — do not show fixture data as live data
  if (serverError) {
    return (
      <div className="page">
        <div className="page__head">
          <div>
            <h1 className="page__title">Activity</h1>
            <div className="page__sub">전체 tenant의 ceremony · 운영 액션 · 보안 이벤트가 실시간으로 모입니다.</div>
          </div>
          <div className="row">
            <button className="btn btn--sm" onClick={handleRefresh}>
              <Icons.Refresh size={12} /> 재시도
            </button>
          </div>
        </div>
        <div className="card" style={{ padding: 40, textAlign: 'center', color: 'var(--text-mute)' }}>
          <Icons.Alert size={24} />
          <div style={{ marginTop: 12, fontWeight: 500 }}>Activity 데이터를 불러오지 못했습니다.</div>
          <div style={{ marginTop: 4, fontSize: 12 }}>서버에 연결할 수 없거나 접근 권한이 없습니다.</div>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Activity</h1>
          <div className="page__sub">전체 tenant의 ceremony · 운영 액션 · 보안 이벤트가 실시간으로 모입니다.</div>
        </div>
        <div className="row">
          <button className="btn btn--sm" onClick={handleRefresh}>
            <Icons.Refresh size={12} /> 새로고침
          </button>
          <button className="btn btn--sm" onClick={() => {
            if (!events || events.length === 0) {
              toast({ kind: 'warn', title: '내보낼 데이터 없음' });
              return;
            }
            downloadCsv(
              `activity-${new Date().toISOString().slice(0, 10)}.csv`,
              ['timestamp', 'tenant', 'type', 'category', 'actor', 'subject'],
              events.map((e) => [e.ts, e.tenantSlug ?? e.tenantId ?? '', e.type, e.category, e.actorId ?? '', e.subjectId]),
            );
          }}>
            <Icons.Download size={12} /> 내보내기
          </button>
        </div>
      </div>

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <MetricCard
          label="활동 (24h)"
          value={fmt(kpi.events24h)}
          sub="전체 tenant 합산"
        />
        <MetricCard
          label="운영 액션 (24h)"
          value={kpi.ops24h}
          sub="admin mutation 전체"
        />
        <MetricCard
          label="보안 이벤트 (24h)"
          value={kpi.security24h}
          sub="signature regression + attestation fail"
        />
        <MetricCard
          label="평균 응답"
          value={kpi.p95Ms != null ? `${kpi.p95Ms}ms` : '—'}
          sub={kpi.p95Ms != null ? `p95 ${kpi.p95Ms}ms` : '측정 중'}
        />
      </div>

      <div className="card" style={{ marginBottom: 16, padding: '10px 14px' }}>
        <div className="row" style={{ gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="muted" style={{ fontSize: 12, fontWeight: 600 }}>카테고리</span>
          <ChipTab active={categoryFilter === 'all'} onClick={() => setCategoryFilter('all')}>전체</ChipTab>
          <ChipTab active={categoryFilter === 'ops'} onClick={() => setCategoryFilter('ops')}>운영</ChipTab>
          <ChipTab active={categoryFilter === 'security'} onClick={() => setCategoryFilter('security')}>보안</ChipTab>

          <span className="muted" style={{ fontSize: 12, fontWeight: 600, marginLeft: 8 }}>테넌트</span>
          <select
            value={selectedTenant ?? ''}
            onChange={(ev) => setSelectedTenant(ev.target.value || undefined)}
            style={{ padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)', fontSize: 12 }}
          >
            <option value="">전체</option>
            {tenants.map((t) => (
              <option key={t.id} value={t.id}>{t.name} ({t.slug})</option>
            ))}
          </select>

          <span className="muted" style={{ fontSize: 12, fontWeight: 600, marginLeft: 8 }}>액션</span>
          <input
            value={actionQuery}
            onChange={(ev) => setActionQuery(ev.target.value)}
            placeholder="액션 검색…"
            style={{ padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)', fontSize: 12, minWidth: 140 }}
          />
        </div>
      </div>

      <div className="grid-2" style={{ gridTemplateColumns: '1fr 320px', gap: 16 }}>
        <div className="card">
          <div className="card__head">
            <h3 className="card__title">최근 이벤트</h3>
          </div>
          <div className="row" style={{ gap: 14, flexWrap: 'wrap', padding: '8px 20px', borderBottom: '1px solid var(--border)' }}>
            <LegendDot tone="danger" label="보안 실패" />
            <LegendDot tone="warning" label="운영 변경" />
            <LegendDot tone="info" label="인증" />
            <LegendDot tone="teal" label="시스템" />
          </div>
          <div>
            {filtered.slice(0, visibleCount).map((e, i) => (
              <div
                key={e.id}
                style={{
                  display: 'flex',
                  gap: 12,
                  padding: '10px 20px',
                  borderBottom:
                    i === Math.min(filtered.length, visibleCount) - 1
                      ? 0
                      : '1px solid var(--border)',
                  alignItems: 'center',
                }}
              >
                <EventDot type={e.type} category={e.category} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="row" style={{ gap: 8 }}>
                    <EventTypeBadge type={e.type} category={e.category} />
                    <button
                      onClick={() => handleOpenTenant(e.tenantId)}
                      className="btn btn--ghost btn--xs"
                      style={{ padding: '1px 6px', fontWeight: 600 }}
                    >
                      {e.tenantName}
                    </button>
                    <span className="muted" style={{ fontSize: 11 }}>
                      · {timeAgo(e.ts)}
                    </span>
                  </div>
                  <div className="muted" style={{ fontSize: 11, marginTop: 3 }}>
                    {eventSentence({
                      action: e.type,
                      actorEmail: e.actorId ?? '',
                      tenantSlug: e.tenantSlug,
                      targetType: null,
                      targetId: e.subjectId,
                    })}
                  </div>
                </div>
                <button className="btn btn--ghost btn--xs" onClick={() => openDetail(e.id)}>
                  <Icons.ChevronRight size={12} />
                </button>
              </div>
            ))}
          </div>
          <div
            style={{
              padding: '10px 14px',
              textAlign: 'center',
              borderTop: '1px solid var(--border)',
            }}
          >
            <button className="btn btn--sm" onClick={async () => {
              if (!events || events.length === 0) return;
              const oldest = events[events.length - 1].ts;
              try {
                const more = await activityApi.fetch(
                  null,
                  categoryFilter === 'all' ? undefined : categoryFilter,
                  oldest,
                  selectedTenant,
                );
                const adapted = adaptServerView(more);
                setEvents([...events, ...adapted.events]);
                // Expand the visible window so newly-loaded rows actually appear in the feed
                setVisibleCount((n) => n + Math.max(adapted.events.length, 24));
              } catch (err: unknown) {
                const msg = err instanceof Error ? err.message : String(err);
                toast({ kind: 'err', title: '추가 로드 실패', message: msg });
              }
            }}>이전 24시간 더 보기</button>
          </div>
        </div>

        <div className="stack-4">
          {(detail || detailLoading) && (
            <DetailPanel detail={detail} loading={detailLoading} onClose={() => setDetail(null)} />
          )}
          <div className="card">
            <div className="card__head">
              <h3 className="card__title">활발한 Tenant</h3>
            </div>
            <div>
              {topTenants.map((t, i) => (
                <button
                  key={t.tenantId}
                  onClick={() => setSelectedTenant(t.tenantId)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '10px 16px',
                    width: '100%',
                    border: 0,
                    background: 'transparent',
                    borderBottom:
                      i === topTenants.length - 1 ? 0 : '1px solid var(--border)',
                    textAlign: 'left',
                    cursor: 'pointer',
                    color: 'var(--text)',
                  }}
                  onMouseEnter={(e) =>
                    (e.currentTarget.style.background = 'var(--surface-2)')
                  }
                  onMouseLeave={(e) =>
                    (e.currentTarget.style.background = 'transparent')
                  }
                >
                  <div
                    style={{
                      width: 26,
                      height: 26,
                      borderRadius: 6,
                      background: 'var(--accent-soft)',
                      color: 'var(--accent)',
                      display: 'grid',
                      placeItems: 'center',
                      fontWeight: 700,
                      fontSize: 11,
                      flex: 'none',
                    }}
                  >
                    {t.tenantName.slice(0, 1).toUpperCase()}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div
                      style={{
                        fontWeight: 500,
                        fontSize: 13,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {t.tenantName}
                    </div>
                    <div className="mono muted" style={{ fontSize: 11 }}>
                      {t.tenantSlug}
                    </div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="mono" style={{ fontWeight: 600, fontSize: 13 }}>
                      {fmt(t.count)}
                    </div>
                    <div className="muted" style={{ fontSize: 10 }}>
                      24h events
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}
