/* global React, Icons, MetricCard, StatusBadge, EventTypeBadge, EventDot, useToast, timeAgo, fmtDateTime, tail, fmt */
const { useState: useState4, useMemo: useMemo4 } = React;

// ===== Activity — cross-tenant 활동 피드 (PLATFORM_OPERATOR 전용) =====
function ActivityPage({ onOpenTenant }) {
  const tenants = window.MOCK.TENANTS;
  const events = window.MOCK.AUDIT_EVENTS;
  const [filter, setFilter] = useState4("all"); // all | mutations | failures
  const [tenantFilter, setTenantFilter] = useState4("all");

  // Synthesize cross-tenant events by spreading our pool across tenants
  const crossEvents = useMemo4(() => {
    const out = [];
    tenants.forEach((tn, i) => {
      events.slice(0, 6).forEach((e, j) => {
        const offset = (i * 7 + j * 3) * 60 * 1000; // skew timestamps
        out.push({
          ...e,
          ts: new Date(Date.now() - offset - i * 240000).toISOString(),
          tenantId: tn.id,
          tenantName: tn.name,
          tenantSlug: tn.slug,
        });
      });
    });
    return out.sort((a, b) => b.ts.localeCompare(a.ts));
  }, [tenants, events]);

  const filtered = crossEvents.filter((e) => {
    if (tenantFilter !== "all" && e.tenantId !== tenantFilter) return false;
    if (filter === "mutations") return ["API_KEY_ISSUED","API_KEY_REVOKED","CREDENTIAL_REVOKED","WEBAUTHN_CONFIG_UPDATED","ATTESTATION_POLICY_UPDATED","TENANT_CREATED"].includes(e.type);
    if (filter === "failures") return ["SIGNATURE_COUNTER_REGRESSION","ATTESTATION_TRUST_FAILED"].includes(e.type);
    return true;
  });

  // Top tenants by recent activity
  const tenantActivity = tenants.map((t) => ({
    ...t,
    count: crossEvents.filter((e) => e.tenantId === t.id).length,
  })).sort((a, b) => b.count - a.count).slice(0, 5);

  const failureCount = crossEvents.filter((e) => ["SIGNATURE_COUNTER_REGRESSION","ATTESTATION_TRUST_FAILED"].includes(e.type)).length;
  const mutationCount = crossEvents.filter((e) => e.actorType === "ADMIN").length;

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Activity</h1>
          <div className="page__sub">전체 tenant의 ceremony · 운영 액션 · 보안 이벤트가 실시간으로 모입니다.</div>
        </div>
        <div className="row">
          <button className="btn btn--sm"><Icons.Refresh size={12} /> 새로고침</button>
          <button className="btn btn--sm"><Icons.Download size={12} /> 내보내기</button>
        </div>
      </div>

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <MetricCard label="활동 (24h)" value={fmt(crossEvents.length * 387)} sub={`${tenants.length}개 tenant 합산`} />
        <MetricCard label="운영 액션 (24h)" value={mutationCount} sub="admin mutation 전체" />
        <MetricCard label="보안 이벤트 (24h)" value={failureCount} sub="signature regression + attestation fail" />
        <MetricCard label="평균 응답" value="18ms" sub="p95 42ms · p99 89ms" />
      </div>

      <div className="grid-2" style={{ gridTemplateColumns: "1fr 320px", gap: 16 }}>
        <div className="card">
          <div className="card__head" style={{ gap: 10, flexWrap: "wrap" }}>
            <div>
              <h3 className="card__title">최근 이벤트</h3>
              <div className="card__sub">필터: <em>{filter === "all" ? "전체" : filter === "mutations" ? "운영 액션만" : "보안 실패만"}</em> · <em>{tenantFilter === "all" ? "모든 tenant" : tenants.find((t) => t.id === tenantFilter)?.name}</em></div>
            </div>
            <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
              <ChipTab active={filter==="all"} onClick={() => setFilter("all")}>전체</ChipTab>
              <ChipTab active={filter==="mutations"} onClick={() => setFilter("mutations")}>운영 액션</ChipTab>
              <ChipTab active={filter==="failures"} onClick={() => setFilter("failures")}>보안 실패</ChipTab>
            </div>
          </div>
          <div>
            {filtered.slice(0, 24).map((e, i) => (
              <div key={i} style={{ display: "flex", gap: 12, padding: "10px 20px", borderBottom: i === Math.min(filtered.length, 24) - 1 ? 0 : "1px solid var(--border)", alignItems: "center" }}>
                <EventDot type={e.type} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="row" style={{ gap: 8 }}>
                    <EventTypeBadge type={e.type} />
                    <button onClick={() => onOpenTenant(e.tenantId)} className="btn btn--ghost btn--xs" style={{ padding: "1px 6px", fontWeight: 600 }}>
                      {e.tenantName}
                    </button>
                    <span className="muted" style={{ fontSize: 11 }}>· {timeAgo(e.ts)}</span>
                  </div>
                  <div className="muted mono" style={{ fontSize: 11, marginTop: 3 }}>
                    actor {tail(e.actorId, 10)} → subject {tail(e.subjectId, 12)}
                  </div>
                </div>
                <button className="btn btn--ghost btn--xs"><Icons.ChevronRight size={12} /></button>
              </div>
            ))}
          </div>
          <div style={{ padding: "10px 14px", textAlign: "center", borderTop: "1px solid var(--border)" }}>
            <button className="btn btn--sm">이전 24시간 더 보기</button>
          </div>
        </div>

        <div className="stack-4">
          <div className="card">
            <div className="card__head"><h3 className="card__title">활발한 Tenant</h3></div>
            <div>
              {tenantActivity.map((t, i) => (
                <button
                  key={t.id}
                  onClick={() => onOpenTenant(t.id)}
                  style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 16px", width: "100%", border: 0, background: "transparent", borderBottom: i === tenantActivity.length - 1 ? 0 : "1px solid var(--border)", textAlign: "left", cursor: "pointer", color: "var(--text)" }}
                  onMouseEnter={(e) => e.currentTarget.style.background = "var(--surface-2)"}
                  onMouseLeave={(e) => e.currentTarget.style.background = "transparent"}
                >
                  <div style={{ width: 26, height: 26, borderRadius: 6, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 11, flex: "none" }}>
                    {t.name.slice(0, 1)}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 500, fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{t.name}</div>
                    <div className="mono muted" style={{ fontSize: 11 }}>{fmt(t.credentials)} credentials</div>
                  </div>
                  <div style={{ textAlign: "right" }}>
                    <div className="mono" style={{ fontWeight: 600, fontSize: 13 }}>{t.count * 387}</div>
                    <div className="muted" style={{ fontSize: 10 }}>24h events</div>
                  </div>
                </button>
              ))}
            </div>
          </div>

          <div className="card">
            <div className="card__head"><h3 className="card__title">Tenant 필터</h3></div>
            <div style={{ padding: 14, display: "flex", gap: 6, flexWrap: "wrap" }}>
              <ChipTab active={tenantFilter==="all"} onClick={() => setTenantFilter("all")}>전체 tenant</ChipTab>
              {tenants.slice(0, 6).map((t) => (
                <ChipTab key={t.id} active={tenantFilter===t.id} onClick={() => setTenantFilter(t.id)}>{t.name}</ChipTab>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function ChipTab({ active, onClick, children }) {
  return (
    <button onClick={onClick} className={active ? "badge badge--accent" : "badge"} style={{
      cursor: "pointer", padding: "3px 9px", fontSize: 11,
      border: active ? "1px solid var(--accent)" : "1px solid var(--border)",
    }}>{children}</button>
  );
}

window.ActivityPage = ActivityPage;
window.ChipTab = ChipTab;
