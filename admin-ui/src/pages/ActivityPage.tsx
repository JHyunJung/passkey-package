import { useState, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { activityApi } from '@/api/activity';
import { activityFixture } from '@/fixtures/activity';
import { useToast } from '@/shell/ToastHost';
import { downloadCsv } from '@/lib/csvExport';
import type { ActivityView, ActivityCategory } from '@/api/types';
import { adaptFeedItems, type RecentActivityEvent } from './tenant/recentActivityAdapter';

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

function tail(str: string | null | undefined, n: number): string {
  if (!str) return '—';
  return str.length <= n ? str : '…' + str.slice(-n);
}

function fmt(n: number | null | undefined): string {
  if (n == null) return '—';
  return n.toLocaleString();
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

// ── Mutation / Security event type sets ──────────────────────────────────────

const MUTATION_TYPES = new Set([
  'API_KEY_ISSUED',
  'API_KEY_REVOKED',
  'CREDENTIAL_REVOKED',
  'WEBAUTHN_CONFIG_UPDATED',
  'ATTESTATION_POLICY_UPDATED',
  'TENANT_CREATED',
]);

const FAILURE_TYPES = new Set([
  'SIGNATURE_COUNTER_REGRESSION',
  'ATTESTATION_TRUST_FAILED',
]);

// ── EventDot ─────────────────────────────────────────────────────────────────

function EventDot({ type }: { type: string }) {
  const color = FAILURE_TYPES.has(type)
    ? 'var(--err, #ef4444)'
    : MUTATION_TYPES.has(type)
    ? 'var(--warn, #f59e0b)'
    : 'var(--accent, #4f46e5)';
  return (
    <div
      style={{
        width: 7,
        height: 7,
        borderRadius: '50%',
        background: color,
        flex: 'none',
      }}
    />
  );
}

// ── EventTypeBadge ────────────────────────────────────────────────────────────

function EventTypeBadge({ type }: { type: string }) {
  const cls = FAILURE_TYPES.has(type)
    ? 'badge badge--err'
    : MUTATION_TYPES.has(type)
    ? 'badge badge--warn'
    : 'badge';
  return <span className={cls} style={{ fontSize: 10 }}>{type}</span>;
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

// ── ActivityPage ──────────────────────────────────────────────────────────────

export default function ActivityPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams] = useSearchParams();
  const tenantFilter = searchParams.get('tenantId') ?? undefined;

  // null = not yet loaded from server; undefined = server error (show empty state)
  const [events, setEvents] = useState<DisplayEvent[] | null>(null);
  const [kpi, setKpi] = useState(activityFixture.kpi);
  const [topTenants, setTopTenants] = useState(activityFixture.topTenants);
  const [serverError, setServerError] = useState(false);

  const [filter, setFilter] = useState<'all' | 'mutations' | 'failures'>('all');
  const [categoryFilter, setCategoryFilter] = useState<ActivityCategory>('all');
  const [visibleCount, setVisibleCount] = useState(24);

  // 5-second polling
  const cancelledRef = useRef(false);
  // Track whether we've had at least one successful server response
  const hasServerDataRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;

    async function fetchOnce() {
      try {
        const view = await activityApi.fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, tenantFilter);
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
  }, [categoryFilter, tenantFilter]);

  // Filtered events for the feed panel
  // Use e.category (server classification) for ops/security filter;
  // type-name sets are only for dot/badge color on fixture events where
  // category is pre-computed from the same sets.
  const displayEvents = events ?? [];
  const filtered = displayEvents.filter((e) => {
    if (filter === 'mutations') return e.category === 'ops';
    if (filter === 'failures') return e.category === 'security';
    return true;
  });

  const failureCount = displayEvents.filter((e) => e.category === 'security').length;
  const mutationCount = displayEvents.filter((e) => e.category === 'ops').length;

  function handleOpenTenant(tenantId: string | null) {
    if (!tenantId) return;
    navigate(`/tenants/${tenantId}`);
  }

  function handleRefresh() {
    activityApi
      .fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, undefined, tenantFilter)
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

      <div className="grid-2" style={{ gridTemplateColumns: '1fr 320px', gap: 16 }}>
        <div className="card">
          <div className="card__head" style={{ gap: 10, flexWrap: 'wrap' }}>
            <div>
              <h3 className="card__title">최근 이벤트</h3>
              <div className="card__sub">
                필터:{' '}
                <em>
                  {filter === 'all'
                    ? '전체'
                    : filter === 'mutations'
                    ? '운영 액션만'
                    : '보안 실패만'}
                </em>
              </div>
            </div>
            <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
              <ChipTab active={filter === 'all'} onClick={() => setFilter('all')}>
                전체
              </ChipTab>
              <ChipTab active={filter === 'mutations'} onClick={() => setFilter('mutations')}>
                운영 액션
              </ChipTab>
              <ChipTab active={filter === 'failures'} onClick={() => setFilter('failures')}>
                보안 실패
              </ChipTab>
            </div>
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
                <EventDot type={e.type} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="row" style={{ gap: 8 }}>
                    <EventTypeBadge type={e.type} />
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
                  <div className="muted mono" style={{ fontSize: 11, marginTop: 3 }}>
                    actor {tail(e.actorId, 10)} → subject {tail(e.subjectId, 12)}
                  </div>
                </div>
                <button className="btn btn--ghost btn--xs" onClick={() => {
                  toast({ kind: 'ok', title: e.type, message: `id: ${e.id} · subject: ${e.subjectId}` });
                }}>
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
                  tenantFilter,
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
          <div className="card">
            <div className="card__head">
              <h3 className="card__title">활발한 Tenant</h3>
            </div>
            <div>
              {topTenants.map((t, i) => (
                <button
                  key={t.tenantId}
                  onClick={() => handleOpenTenant(t.tenantId)}
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

          <div className="card">
            <div className="card__head">
              <h3 className="card__title">카테고리 필터</h3>
            </div>
            <div style={{ padding: 14, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <ChipTab
                active={categoryFilter === 'all'}
                onClick={() => setCategoryFilter('all')}
              >
                전체
              </ChipTab>
              <ChipTab
                active={categoryFilter === 'ops'}
                onClick={() => setCategoryFilter('ops')}
              >
                운영
              </ChipTab>
              <ChipTab
                active={categoryFilter === 'security'}
                onClick={() => setCategoryFilter('security')}
              >
                보안
              </ChipTab>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
