import { useCallback, useEffect, useRef, useState } from 'react';
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';
import { getActivity } from '../api/client';
import { formatDateTime } from '../lib/formatDateTime';
import type { ActivityView, ActivityCategory, ActivityEvent, ActivityTopTenant } from '../api/types';

const POLL_MS = 5000;
const FEED_MAX = 200;

export default function Activity() {
    return <PlatformOnlyGuard><ActivityInner /></PlatformOnlyGuard>;
}

function ActivityInner() {
    const [view, setView] = useState<ActivityView | null>(null);
    const [category, setCategory] = useState<ActivityCategory>('all');
    const [error, setError] = useState<string | null>(null);
    const lastIdRef = useRef<string | null>(null);

    const loadInitial = useCallback(async () => {
        setError(null);
        try {
            const v = await getActivity({ category });
            setView(v);
            lastIdRef.current = v.feed[0]?.id ?? null;
        } catch (e) {
            setError((e as Error)?.message ?? 'load failed');
        }
    }, [category]);

    const poll = useCallback(async () => {
        try {
            const v = await getActivity({
                category,
                sinceId: lastIdRef.current ?? undefined,
            });
            setView((prev) => {
                if (!prev) return v;
                const merged = [...v.feed, ...prev.feed].slice(0, FEED_MAX);
                return { kpi: v.kpi, top5: v.top5, feed: merged };
            });
            if (v.feed.length > 0) lastIdRef.current = v.feed[0].id;
        } catch { /* silent — 첫 로드 banner 외에는 무시 */ }
    }, [category]);

    useEffect(() => { loadInitial(); }, [loadInitial]);
    useEffect(() => {
        const tick = setInterval(poll, POLL_MS);
        return () => clearInterval(tick);
    }, [poll]);

    if (error) return <div className="banner banner--danger">{error}</div>;
    if (!view) return <div className="muted">불러오는 중…</div>;

    return (
        <div className="stack-4">
            <h1 style={{ margin: 0 }}>Activity</h1>

            <div className="row" style={{ gap: 12 }}>
                <KpiCard label="24h 활동량"      value={view.kpi.events24h} />
                <KpiCard label="운영 액션 24h"   value={view.kpi.ops24h} />
                <KpiCard label="보안 이벤트 24h" value={view.kpi.security24h} accent="danger" />
                <KpiCard label="p95 응답 (ms)"   value={view.kpi.p95Ms ?? 'N/A'} muted />
            </div>

            <div className="row" style={{ gap: 16, alignItems: 'flex-start' }}>
                <Top5Panel top5={view.top5} />
                <FeedPanel
                    events={view.feed}
                    category={category}
                    onCategoryChange={(c) => {
                        setCategory(c);
                        lastIdRef.current = null;
                    }}
                />
            </div>
        </div>
    );
}

function KpiCard({ label, value, accent, muted }:
                  { label: string; value: number | string; accent?: 'danger'; muted?: boolean }) {
    return (
        <div className="card" style={{ flex: 1, padding: 16 }}>
            <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>{label}</div>
            <div style={{
                fontSize: 28,
                fontWeight: 600,
                color: muted ? 'var(--text-mute)' :
                       accent === 'danger' ? 'var(--danger)' : 'var(--text)',
            }}>{value}</div>
        </div>
    );
}

function Top5Panel({ top5 }: { top5: ActivityTopTenant[] }) {
    return (
        <div className="card" style={{ width: 280, padding: 16 }}>
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12 }}>활발한 Tenant Top 5</div>
            {top5.length === 0 && <div className="muted" style={{ fontSize: 12 }}>최근 24h 활동 없음</div>}
            {top5.map((t) => (
                <div key={t.tenantId} className="row" style={{
                    justifyContent: 'space-between', padding: '6px 0',
                    borderBottom: '1px solid var(--border-mute)',
                }}>
                    <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{t.slug}</span>
                    <span className="badge">{t.count}</span>
                </div>
            ))}
        </div>
    );
}

function FeedPanel({ events, category, onCategoryChange }:
                    { events: ActivityEvent[]; category: ActivityCategory;
                      onCategoryChange: (c: ActivityCategory) => void }) {
    return (
        <div className="card" style={{ flex: 1, padding: 16 }}>
            <div className="row" style={{ justifyContent: 'space-between', marginBottom: 12 }}>
                <div style={{ fontSize: 13, fontWeight: 600 }}>이벤트 스트림</div>
                <CategoryChips current={category} onChange={onCategoryChange} />
            </div>
            <div style={{ maxHeight: 480, overflowY: 'auto' }}>
                {events.length === 0 && (
                    <div className="muted" style={{ textAlign: 'center', padding: 24 }}>
                        이벤트 없음
                    </div>
                )}
                {events.map((e) => (
                    <div key={e.id} style={{
                        padding: '8px 0',
                        borderBottom: '1px solid var(--border-mute)',
                    }}>
                        <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                            <span className={'badge badge--' + e.category}>{e.action}</span>
                            <span className="muted" style={{ fontSize: 11 }}>
                                {e.tenantSlug ?? 'platform'}
                            </span>
                            <span className="muted" style={{ fontSize: 11, marginLeft: 'auto' }}>
                                {formatDateTime(e.createdAt)}
                            </span>
                        </div>
                        <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
                            {e.actorEmail}
                            {e.targetType && <> · {e.targetType}{e.targetId && '/' + e.targetId.slice(0, 8)}…</>}
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
}

function CategoryChips({ current, onChange }:
                        { current: ActivityCategory; onChange: (c: ActivityCategory) => void }) {
    const chips: { key: ActivityCategory; label: string }[] = [
        { key: 'all',      label: '전체' },
        { key: 'ops',      label: '운영' },
        { key: 'security', label: '보안' },
    ];
    return (
        <div className="row" style={{ gap: 6 }}>
            {chips.map((c) => (
                <button
                    key={c.key}
                    className={'btn btn--sm ' + (current === c.key ? 'btn--primary' : 'btn--ghost')}
                    onClick={() => onChange(c.key)}
                >{c.label}</button>
            ))}
        </div>
    );
}
