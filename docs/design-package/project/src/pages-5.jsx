/* global React, Icons, MetricCard, Dialog, useToast, ChipTab, fmt, fmtDateTime, timeAgo */
const { useState: useState5, useMemo: useMemo5 } = React;

// ===== Audit Chain Monitor — 전 tenant 무결성 보드 =====
function AuditChainPage({ onOpenTenant }) {
  const tenants = window.MOCK.TENANTS;
  const toast = useToast();
  const [running, setRunning] = useState5(false);
  const [showReport, setShowReport] = useState5(false);

  // Synthesize chain status per tenant
  const chainState = useMemo5(() => tenants.map((t, i) => {
    const rowsK = 1 + ((i * 173) % 9);
    const verifiedRows = Math.floor((t.credentials || 1000) * (4 + rowsK) / 10);
    return {
      ...t,
      rows: verifiedRows,
      intact: t.slug !== "pied-piper",
      lastVerifiedAt: new Date(Date.now() - (60 * (i + 1) + 30) * 1000).toISOString(),
      avgInterval: 60,
      tampered: t.slug === "pied-piper" ? ["en_01H9KX2..", "en_01H9KX9.."] : [],
    };
  }), [tenants]);

  const intactCount = chainState.filter((c) => c.intact).length;
  const totalRows = chainState.reduce((a, c) => a + c.rows, 0);
  const tamperedTenant = chainState.find((c) => !c.intact);

  function runAll() {
    setRunning(true);
    setTimeout(() => {
      setRunning(false);
      toast({ kind: "ok", title: "전체 tenant 검증 완료", message: `${intactCount}/${chainState.length} 무결 · 총 ${fmt(totalRows)} 행`, traceId: "tr_chain_001" });
    }, 1800);
  }

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Audit Chain Monitor</h1>
          <div className="page__sub">전체 tenant의 SHA-256 hash chain 무결성 상태. scheduler가 60초마다 자동 검증합니다.</div>
        </div>
        <div className="row">
          <button className="btn btn--sm" onClick={() => setShowReport(true)}><Icons.Download size={12} /> 월간 보고서</button>
          <button className="btn btn--primary btn--sm" onClick={runAll} disabled={running}>
            <Icons.Hash size={12} /> {running ? "검증 중…" : "전체 즉시 검증"}
          </button>
        </div>
      </div>

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <MetricCard label="무결 / 전체" value={`${intactCount} / ${chainState.length}`} sub={tamperedTenant ? `위변조 의심: ${tamperedTenant.name}` : "전체 무결"} />
        <MetricCard label="검증된 audit row" value={fmt(totalRows)} sub="누적 chain length" />
        <MetricCard label="검증 주기" value="60s" sub="background scheduler · prometheus 연동" />
        <MetricCard label="평균 chain 검증" value="284ms" sub="100k rows 기준 · p99 920ms" />
      </div>

      {tamperedTenant && (
        <div className="card" style={{ borderColor: "var(--danger)", background: "var(--danger-soft)", marginBottom: 20 }}>
          <div className="card__body" style={{ display: "flex", gap: 12, alignItems: "center" }}>
            <div style={{ width: 38, height: 38, borderRadius: 10, background: "var(--danger)", color: "white", display: "grid", placeItems: "center", flex: "none" }}>
              <Icons.Alert size={20} />
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, color: "var(--danger)" }}>위변조 의심 — 즉시 조사 필요</div>
              <div style={{ fontSize: 13, color: "var(--text)", marginTop: 4 }}>
                <strong>{tamperedTenant.name}</strong> tenant에서 {tamperedTenant.tampered.length}개 audit row의 hash가 일치하지 않습니다. DBA + 보안팀 알림 필요.
              </div>
            </div>
            <button className="btn" onClick={() => onOpenTenant(tamperedTenant.id)}>tenant 열기 <Icons.ChevronRight size={12} /></button>
            <button className="btn btn--danger btn--sm"><Icons.Alert size={12} /> Incident 생성</button>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card__head">
          <h3 className="card__title">Tenant별 Chain 상태</h3>
          <div className="row">
            <span className="muted" style={{ fontSize: 12 }}>최근 60초 기준</span>
            <span className="badge badge--success badge--dot">INTACT {intactCount}</span>
            {chainState.length - intactCount > 0 && <span className="badge badge--danger badge--dot">TAMPERED {chainState.length - intactCount}</span>}
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Tenant</th>
              <th>Status</th>
              <th style={{ textAlign: "right" }}>Verified Rows</th>
              <th>마지막 검증</th>
              <th>Chain (시각화)</th>
              <th style={{ textAlign: "right" }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {chainState.map((c) => (
              <tr key={c.id}>
                <td>
                  <button onClick={() => onOpenTenant(c.id)} style={{ display: "flex", alignItems: "center", gap: 8, background: "transparent", border: 0, padding: 0, cursor: "pointer", color: "var(--text)" }}>
                    <div style={{ width: 22, height: 22, borderRadius: 5, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 10, flex: "none" }}>{c.name.slice(0, 1)}</div>
                    <span style={{ fontWeight: 600, fontSize: 13 }}>{c.name}</span>
                  </button>
                </td>
                <td>
                  {c.intact ? (
                    <span className="badge badge--success badge--dot">INTACT</span>
                  ) : (
                    <span className="badge badge--danger badge--dot">TAMPERED · {c.tampered.length}</span>
                  )}
                </td>
                <td style={{ textAlign: "right" }} className="mono">{fmt(c.rows)}</td>
                <td className="muted">{timeAgo(c.lastVerifiedAt)}</td>
                <td><ChainSparkline intact={c.intact} /></td>
                <td style={{ textAlign: "right" }}>
                  <button className="btn btn--xs" onClick={() => onOpenTenant(c.id)}>열기</button>
                  <button className="btn btn--xs" style={{ marginLeft: 4 }}>검증</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <MonthlyReportDialog open={showReport} onClose={() => setShowReport(false)} chainState={chainState} />
    </div>
  );
}

// Tiny inline sparkline showing 24 hourly verification ticks. Tampered runs show a red marker.
function ChainSparkline({ intact }) {
  const ticks = Array.from({ length: 24 }, (_, i) => i);
  return (
    <div style={{ display: "flex", gap: 2, alignItems: "flex-end", height: 18 }}>
      {ticks.map((i) => {
        const broken = !intact && (i === 14 || i === 15);
        const h = 6 + (i * 7919) % 12;
        return (
          <span key={i} style={{
            width: 4, height: h,
            background: broken ? "var(--danger)" : "var(--success)",
            borderRadius: 1, opacity: broken ? 1 : 0.55,
          }} />
        );
      })}
    </div>
  );
}

function MonthlyReportDialog({ open, onClose, chainState }) {
  const [from, setFrom] = useState5("2026-04-01");
  const [to, setTo] = useState5("2026-04-30");
  return (
    <Dialog open={open} onClose={onClose} wide title="월간 무결성 보고서 발급"
      sub="기간 내 전체 tenant의 hash chain 검증 결과를 PDF로 묶어 내보냅니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={onClose}>PDF 생성 → 다운로드</button>
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
      <div style={{ border: "1px solid var(--border)", borderRadius: 8, overflow: "hidden" }}>
        {chainState.map((c, i) => (
          <div key={c.id} style={{ display: "flex", alignItems: "center", padding: "8px 14px", borderBottom: i === chainState.length - 1 ? 0 : "1px solid var(--border)", fontSize: 13 }}>
            <input type="checkbox" defaultChecked style={{ marginRight: 10 }} />
            <span style={{ flex: 1 }}>{c.name}</span>
            <span className="mono muted" style={{ fontSize: 11, marginRight: 12 }}>{fmt(c.rows)} rows</span>
            {c.intact ? <span className="badge badge--success">INTACT</span> : <span className="badge badge--danger">TAMPERED</span>}
          </div>
        ))}
      </div>
      <div style={{ marginTop: 12, padding: 10, background: "var(--info-soft)", color: "var(--info)", borderRadius: 6, fontSize: 12, display: "flex", gap: 8 }}>
        <Icons.Info size={14} />
        <span>보고서는 compliance 감사 응답에 사용 가능합니다. 생성에 약 30초 소요 (v1.1).</span>
      </div>
    </Dialog>
  );
}

window.AuditChainPage = AuditChainPage;
