/* global React, Icons, BrandMark, Dialog, useToast, EmptyState, StatusBadge, CopyBtn, timeAgo, fmtDateTime, tail, fmt */
const { useState, useMemo, useRef } = React;

// ===================== Login =====================
function LoginPage({ onLogin }) {
  const [email, setEmail] = useState("jhyun@crosscert.com");
  const [pw, setPw] = useState("••••••••••");
  const [role, setRole] = useState("PLATFORM_OPERATOR");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  function submit(e) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    setTimeout(() => {
      setLoading(false);
      if (!pw || pw.length < 4) { setError({ code: "A001", message: "이메일 또는 비밀번호가 올바르지 않습니다." }); return; }
      onLogin(role === "PLATFORM_OPERATOR" ? "platform" : "rp");
    }, 480);
  }

  return (
    <div style={{
      minHeight: "100vh",
      display: "grid", gridTemplateColumns: "1.05fr 1fr",
      background: "var(--bg)",
    }}>
      {/* Marketing-side panel */}
      <div style={{
        position: "relative", overflow: "hidden",
        background: "linear-gradient(135deg, #1c1942 0%, #2e2a78 45%, #4f46e5 100%)",
        color: "white", padding: "56px 64px",
        display: "flex", flexDirection: "column", justifyContent: "space-between",
      }}>
        <div className="row" style={{ gap: 10 }}>
          <BrandMark size={28} />
          <div style={{ fontWeight: 700, letterSpacing: "-0.01em", fontSize: 15 }}>Crosscert Passkey</div>
        </div>

        <div>
          <div style={{ fontSize: 13, opacity: 0.7, fontFamily: "var(--mono)", marginBottom: 12 }}>v1.0 · multi-tenant FIDO2 server</div>
          <h1 style={{ fontSize: 38, fontWeight: 600, letterSpacing: "-0.02em", lineHeight: 1.15, margin: 0, maxWidth: 480 }}>
            패스키 인증을<br />운영하는 콘솔.
          </h1>
          <p style={{ fontSize: 14, opacity: 0.8, marginTop: 16, maxWidth: 460, lineHeight: 1.6 }}>
            tenant 온보딩, API key 회수, credential 폐기, audit hash chain 검증까지 한 곳에서.
            RP의 다음 ceremony가 시작되기 전에 끝낼 수 있도록.
          </p>
          <div style={{ display: "flex", gap: 20, marginTop: 28 }}>
            <Stat label="활성 tenant" value="58" />
            <Stat label="ceremony / 24h" value="2.4M" />
            <Stat label="chain 무결성" value="100%" />
          </div>
        </div>

        <div style={{ fontSize: 12, opacity: 0.6 }}>© 2026 Crosscert · 본 콘솔 접근은 모두 audit log에 기록됩니다.</div>

        {/* abstract bg shape */}
        <svg style={{ position: "absolute", right: -120, bottom: -140, width: 540, opacity: 0.18 }} viewBox="0 0 200 200">
          <defs><linearGradient id="lg1" x1="0" x2="1" y1="0" y2="1"><stop stopColor="#fff" /><stop offset="1" stopColor="#fff" stopOpacity="0" /></linearGradient></defs>
          <circle cx="100" cy="100" r="80" stroke="url(#lg1)" strokeWidth="1" fill="none" />
          <circle cx="100" cy="100" r="60" stroke="url(#lg1)" strokeWidth="1" fill="none" />
          <circle cx="100" cy="100" r="40" stroke="url(#lg1)" strokeWidth="1" fill="none" />
          <circle cx="100" cy="100" r="20" stroke="url(#lg1)" strokeWidth="1" fill="none" />
        </svg>
      </div>

      {/* Form side */}
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", padding: "40px" }}>
        <form onSubmit={submit} style={{ width: "100%", maxWidth: 380 }}>
          <h2 style={{ fontSize: 24, fontWeight: 600, letterSpacing: "-0.01em", margin: 0 }}>관리자 로그인</h2>
          <p style={{ fontSize: 13, color: "var(--text-mute)", marginTop: 6, marginBottom: 28 }}>
            Crosscert Passkey 콘솔에 접근하려면 운영자 계정으로 로그인하세요.
          </p>

          <div className="stack-3">
            <div>
              <label className="label">이메일</label>
              <input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
            </div>
            <div>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end" }}>
                <label className="label">비밀번호</label>
                <a href="#" style={{ fontSize: 11, color: "var(--accent)", textDecoration: "none" }}>관리자에게 재설정 요청</a>
              </div>
              <input className="input" type="password" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="current-password" />
            </div>

            {/* Role switcher — only for demo */}
            <div>
              <label className="label">데모: 어떤 role로 로그인할까요?</label>
              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8 }}>
                {[
                  { v: "PLATFORM_OPERATOR", t: "Platform Operator", s: "Crosscert 사내" },
                  { v: "RP_ADMIN", t: "RP Admin", s: "Acme Corp" },
                ].map((opt) => (
                  <button
                    key={opt.v}
                    type="button"
                    onClick={() => setRole(opt.v)}
                    style={{
                      padding: "8px 10px",
                      borderRadius: 8,
                      border: `1px solid ${role === opt.v ? "var(--accent)" : "var(--border)"}`,
                      background: role === opt.v ? "var(--accent-soft)" : "var(--surface)",
                      color: role === opt.v ? "var(--accent)" : "var(--text)",
                      cursor: "pointer",
                      textAlign: "left",
                    }}
                  >
                    <div style={{ fontSize: 12, fontWeight: 600 }}>{opt.t}</div>
                    <div style={{ fontSize: 11, color: "var(--text-mute)", marginTop: 2 }}>{opt.s}</div>
                  </button>
                ))}
              </div>
            </div>

            {error && (
              <div style={{ display: "flex", gap: 8, padding: "10px 12px", background: "var(--danger-soft)", color: "var(--danger)", borderRadius: 8, fontSize: 12 }}>
                <Icons.Alert size={14} />
                <div>
                  <div style={{ fontWeight: 600 }}>로그인 실패</div>
                  <div style={{ opacity: 0.85, marginTop: 2 }}>{error.code} · {error.message}</div>
                </div>
              </div>
            )}

            <button type="submit" disabled={loading} className="btn btn--primary" style={{ height: 38, justifyContent: "center", marginTop: 4 }}>
              {loading ? "확인 중…" : "로그인"}
            </button>
          </div>

          <div style={{ marginTop: 24, padding: "10px 12px", background: "var(--surface-3)", borderRadius: 8, fontSize: 11, color: "var(--text-mute)", display: "flex", gap: 8 }}>
            <Icons.Info size={14} />
            <span>30분 동안 활동이 없으면 자동 로그아웃됩니다. 모든 mutation은 audit chain에 기록됩니다.</span>
          </div>
        </form>
      </div>
    </div>
  );
}
function Stat({ label, value }) {
  return (
    <div>
      <div style={{ fontSize: 11, opacity: 0.7, letterSpacing: "0.06em", textTransform: "uppercase", fontWeight: 600 }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, marginTop: 4 }}>{value}</div>
    </div>
  );
}

// ===================== Tenants list =====================
function TenantsListPage({ tenants, onOpen, onCreate }) {
  const [q, setQ] = useState("");
  const [showNew, setShowNew] = useState(false);
  const filtered = useMemo(() => tenants.filter((t) => t.name.toLowerCase().includes(q.toLowerCase()) || t.slug.includes(q.toLowerCase())), [q, tenants]);
  const totalCredentials = tenants.reduce((a, t) => a + t.credentials, 0);
  const totalKeys = tenants.reduce((a, t) => a + t.apiKeys, 0);
  const totalActive = tenants.filter((t) => t.status === "ACTIVE").length;

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Tenants</h1>
          <div className="page__sub">RP 회사별 격리된 Passkey 환경. 모든 데이터는 tenant_id로 row-level 분리되어 있습니다.</div>
        </div>
        <button className="btn btn--primary" onClick={() => setShowNew(true)}>
          <Icons.Plus size={14} /> 신규 tenant
        </button>
      </div>

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <MetricCard label="활성 Tenant" value={totalActive} sub={`전체 ${tenants.length}건`} />
        <MetricCard label="등록 Credential" value={fmt(totalCredentials)} sub="모든 tenant 합산" />
        <MetricCard label="유효 API Key" value={totalKeys} sub="ACTIVE 상태만" />
        <MetricCard label="24h ceremony" value="2.4M" sub="평균 응답 18ms" />
      </div>

      <div className="card">
        <div className="card__head" style={{ gap: 8 }}>
          <div className="row" style={{ gap: 10 }}>
            <div style={{ position: "relative", width: 280 }}>
              <span style={{ position: "absolute", left: 9, top: "50%", transform: "translateY(-50%)", color: "var(--text-mute)" }}><Icons.Search size={13} /></span>
              <input className="input" placeholder="name · slug 검색" value={q} onChange={(e) => setQ(e.target.value)} style={{ paddingLeft: 28, height: 30 }} />
            </div>
            <button className="btn btn--sm"><Icons.Filter size={12} /> 필터</button>
            <span className="muted" style={{ fontSize: 12 }}>{filtered.length} / {tenants.length}건</span>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn btn--sm"><Icons.Download size={12} /> CSV</button>
          </div>
        </div>

        <table className="table">
          <thead>
            <tr>
              <th>Tenant</th>
              <th>Slug</th>
              <th>RP ID</th>
              <th style={{ textAlign: "right" }}>Credentials</th>
              <th style={{ textAlign: "right" }}>API Keys</th>
              <th>Status</th>
              <th>마지막 이벤트</th>
              <th>생성일</th>
              <th style={{ width: 40 }}></th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((t) => (
              <tr key={t.id} onClick={() => onOpen(t.id)} style={{ cursor: "pointer" }}>
                <td>
                  <div className="row">
                    <div style={{ width: 26, height: 26, borderRadius: 6, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 11, flex: "none" }}>
                      {t.name.slice(0, 1)}
                    </div>
                    <div className="stack-1">
                      <div style={{ fontWeight: 600 }}>{t.name}</div>
                      <div className="mono muted" style={{ fontSize: 11 }}>{tail(t.id, 8)}</div>
                    </div>
                  </div>
                </td>
                <td className="mono">{t.slug}</td>
                <td><span className="mono" style={{ fontSize: 12 }}>{t.rpId}</span></td>
                <td style={{ textAlign: "right" }} className="mono">{fmt(t.credentials)}</td>
                <td style={{ textAlign: "right" }} className="mono">{t.apiKeys}</td>
                <td><StatusBadge status={t.status} /></td>
                <td><span className="muted" style={{ fontSize: 12 }}>{timeAgo(t.lastEvent)}</span></td>
                <td><span className="muted" style={{ fontSize: 12 }}>{fmtDateTime(t.createdAt)}</span></td>
                <td><button className="btn btn--ghost btn--xs" onClick={(e) => { e.stopPropagation(); onOpen(t.id); }}><Icons.ChevronRight size={14} /></button></td>
              </tr>
            ))}
          </tbody>
        </table>

        <div style={{ padding: "10px 14px", display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: 12, color: "var(--text-mute)", borderTop: "1px solid var(--border)" }}>
          <span>page 1 of 1 · 9건</span>
          <div className="row" style={{ gap: 4 }}>
            <button className="btn btn--xs" disabled><Icons.ChevronLeft size={12} /></button>
            <button className="btn btn--xs" disabled><Icons.ChevronRight size={12} /></button>
          </div>
        </div>
      </div>

      <NewTenantDialog open={showNew} onClose={() => setShowNew(false)} onCreate={onCreate} />
    </div>
  );
}

function MetricCard({ label, value, sub, delta }) {
  return (
    <div className="card" style={{ padding: 16 }}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      <div className="metric-delta">{sub}{delta && <span style={{ color: delta > 0 ? "var(--success)" : "var(--danger)", marginLeft: 6 }}>{delta > 0 ? "▲" : "▼"} {Math.abs(delta)}%</span>}</div>
    </div>
  );
}

function NewTenantDialog({ open, onClose, onCreate }) {
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [touched, setTouched] = useState(false);
  const toast = useToast();
  const slugRe = /^[a-z][a-z0-9-]{1,62}$/;
  const slugOk = slugRe.test(slug);
  function generate(n) {
    const s = n.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40);
    setSlug(s);
  }
  function submit() {
    setTouched(true);
    if (!name || !slugOk) return;
    onCreate({ name, slug });
    toast({ kind: "ok", title: "Tenant가 생성되었습니다.", message: `${name} (${slug})`, traceId: "tr_8c39a1" });
    onClose();
    setName(""); setSlug(""); setTouched(false);
  }
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="신규 Tenant 생성"
      sub="새 RP를 온보딩합니다. 생성 후 WebAuthn 설정과 API key 발급으로 이어집니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={submit}>생성하고 설정으로 이동</button>
      </>}
    >
      <div className="stack-3">
        <div>
          <label className="label">표시 이름</label>
          <input className="input" placeholder="예: Acme Corp" value={name} onChange={(e) => { setName(e.target.value); if (!slug) generate(e.target.value); }} />
        </div>
        <div>
          <label className="label">Slug (URL-safe ID)</label>
          <input className="input mono" placeholder="acme-corp" value={slug} onChange={(e) => setSlug(e.target.value.toLowerCase())} />
          <div className="hint">
            {touched && slug && !slugOk
              ? <span style={{ color: "var(--danger)" }}>형식 오류: 소문자/숫자/하이픈, 첫 글자는 영문, 2~63자</span>
              : <>패턴 <code style={{ background: "var(--surface-3)", padding: "1px 4px", borderRadius: 3 }}>^[a-z][a-z0-9-]{`{1,62}`}$</code>. 한 번 생성되면 변경 불가합니다.</>}
          </div>
        </div>

        <div style={{ padding: 12, background: "var(--surface-3)", borderRadius: 8, fontSize: 12, color: "var(--text-soft)" }}>
          <div style={{ fontWeight: 600, marginBottom: 6, display: "flex", alignItems: "center", gap: 6 }}>
            <Icons.Info size={13} /> 다음 단계
          </div>
          <ol style={{ margin: 0, paddingLeft: 18, lineHeight: 1.8 }}>
            <li>WebAuthn config — rpId, origins, UV 정책 설정</li>
            <li>AAGUID 정책 — 시작은 <code>ANY</code> 권장, 이후 ALLOWLIST로 좁힘</li>
            <li>API key 발급 — plaintext는 1회만 노출됩니다</li>
          </ol>
        </div>
      </div>
    </Dialog>
  );
}

Object.assign(window, { LoginPage, TenantsListPage, MetricCard });
