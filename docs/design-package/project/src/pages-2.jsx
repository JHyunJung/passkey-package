/* global React, Icons, Dialog, useToast, EmptyState, StatusBadge, CopyBtn, MetricCard, timeAgo, fmtDateTime, tail, fmt */
const { useState: useState2, useMemo: useMemo2, useEffect: useEffect2 } = React;

// ===================== Tenant Detail (shell with tab content) =====================
function TenantDetailPage({ tenant, currentTab, onTabChange, me }) {
  return (
    <div className="page">
      <TenantHeader tenant={tenant} />
      <TenantTabs current={currentTab} onChange={onTabChange} />
      <div className="stack-4">
        {currentTab === "overview" && <TenantOverview tenant={tenant} />}
        {currentTab === "webauthn" && <WebauthnConfigTab tenant={tenant} />}
        {currentTab === "aaguid" && <AaguidPolicyTab tenant={tenant} />}
        {currentTab === "apikeys" && <ApiKeysTab tenant={tenant} />}
        {currentTab === "credentials" && <CredentialsTab tenant={tenant} />}
        {currentTab === "audit" && <AuditTab tenant={tenant} />}
        {currentTab === "funnel" && <FunnelTab tenant={tenant} />}
      </div>
    </div>
  );
}

function TenantHeader({ tenant }) {
  return (
    <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", marginBottom: 20, gap: 16 }}>
      <div className="row" style={{ gap: 14, alignItems: "flex-start" }}>
        <div style={{ width: 44, height: 44, borderRadius: 10, background: "linear-gradient(135deg, var(--accent), var(--accent-hover))", color: "white", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 20, letterSpacing: "-0.02em" }}>
          {tenant.name.slice(0, 1)}
        </div>
        <div className="stack-1">
          <div className="row" style={{ gap: 8 }}>
            <h1 className="page__title" style={{ marginBottom: 0 }}>{tenant.name}</h1>
            <StatusBadge status={tenant.status} />
          </div>
          <div className="row" style={{ gap: 16, fontSize: 12, color: "var(--text-mute)", marginTop: 4 }}>
            <span className="mono">{tenant.id}</span>
            <span>·</span>
            <span className="mono">slug: {tenant.slug}</span>
            <span>·</span>
            <span>생성 {fmtDateTime(tenant.createdAt)}</span>
          </div>
        </div>
      </div>
      <div className="row">
        <button className="btn btn--sm"><Icons.ExternalLink size={12} /> RP 사이트 열기</button>
        <button className="btn btn--sm"><Icons.Refresh size={12} /> Refresh</button>
        <button className="btn btn--sm"><Icons.Dots size={14} /></button>
      </div>
    </div>
  );
}

function TenantTabs({ current, onChange }) {
  const tabs = [
    { id: "overview", label: "개요" },
    { id: "webauthn", label: "WebAuthn" },
    { id: "aaguid", label: "AAGUID 정책" },
    { id: "apikeys", label: "API Keys" },
    { id: "credentials", label: "Credentials" },
    { id: "audit", label: "Audit Logs" },
    { id: "funnel", label: "Funnel" },
  ];
  return (
    <div className="tabs">
      {tabs.map((t) => (
        <button key={t.id} className={`tabs__btn ${current === t.id ? "tabs__btn--active" : ""}`} onClick={() => onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

// ===================== Overview =====================
function TenantOverview({ tenant }) {
  const f = window.MOCK.FUNNEL;
  return (
    <div className="stack-4">
      <div className="grid-4">
        <MetricCard label="등록 Credential" value={fmt(tenant.credentials)} sub="활성 + 회수 포함" />
        <MetricCard label="유효 API Key" value={tenant.apiKeys} sub="ACTIVE 상태" />
        <MetricCard label="등록 성공률 (7d)" value={`${(f.registration.ratio * 100).toFixed(1)}%`} sub={`${fmt(f.registration.success)} / ${fmt(f.registration.attempts)} 시도`} />
        <MetricCard label="인증 성공률 (7d)" value={`${(f.authentication.ratio * 100).toFixed(1)}%`} sub={`${fmt(f.authentication.success)} / ${fmt(f.authentication.attempts)} 시도`} />
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card__head"><h3 className="card__title">WebAuthn 요약</h3><button className="btn btn--sm">편집 <Icons.ChevronRight size={12} /></button></div>
          <div className="card__body stack-3">
            <KV k="rpId" v={<span className="mono">acme.example.com</span>} />
            <KV k="rpName" v="Acme Corp" />
            <KV k="origins" v={
              <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
                <span className="chip mono">https://acme.example.com</span>
                <span className="chip mono">https://app.acme.example.com</span>
              </div>
            } />
            <KV k="userVerification" v={<span className="badge badge--accent">REQUIRED</span>} />
            <KV k="attestation" v={<span className="badge">DIRECT</span>} />
            <KV k="timeout" v="60s" />
          </div>
        </div>

        <div className="card">
          <div className="card__head"><h3 className="card__title">최근 활동</h3><button className="btn btn--sm">전체 보기 <Icons.ChevronRight size={12} /></button></div>
          <div style={{ padding: "0" }}>
            {window.MOCK.AUDIT_EVENTS.slice(0, 5).map((e, i) => (
              <div key={i} style={{ display: "flex", gap: 10, padding: "10px 20px", borderBottom: i === 4 ? 0 : "1px solid var(--border)", alignItems: "flex-start" }}>
                <EventDot type={e.type} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12, fontWeight: 600 }}>{e.type}</div>
                  <div className="mono muted" style={{ fontSize: 11, marginTop: 2, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{tail(e.subjectId, 16)}</div>
                </div>
                <div className="muted" style={{ fontSize: 11 }}>{timeAgo(e.ts)}</div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <ChainStatusCard />
    </div>
  );
}

function KV({ k, v }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "120px 1fr", gap: 12, alignItems: "center", fontSize: 13 }}>
      <div className="muted" style={{ fontSize: 12 }}>{k}</div>
      <div>{v}</div>
    </div>
  );
}

function EventDot({ type }) {
  const map = {
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

function ChainStatusCard() {
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
          <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>마지막 자동 검증 · 2분 전 · 1,284,920 행 SHA-256 chain · 위변조 0건</div>
        </div>
        <button className="btn btn--sm"><Icons.Hash size={12} /> 수동 검증</button>
        <button className="btn btn--sm"><Icons.Download size={12} /> 월간 보고서</button>
      </div>
    </div>
  );
}

// ===================== WebAuthn Config =====================
function WebauthnConfigTab({ tenant }) {
  const cur = window.MOCK.WEBAUTHN_CONFIG;
  const [draft, setDraft] = useState2({ ...cur });
  const [originInput, setOriginInput] = useState2("");
  const [showDiff, setShowDiff] = useState2(false);
  const toast = useToast();

  function addOrigin() {
    const v = originInput.trim();
    if (!v) return;
    if (draft.origins.includes(v)) return;
    setDraft({ ...draft, origins: [...draft.origins, v] });
    setOriginInput("");
  }
  function removeOrigin(o) {
    setDraft({ ...draft, origins: draft.origins.filter((x) => x !== o) });
  }
  const dirty = JSON.stringify(cur) !== JSON.stringify(draft);
  const changes = diffObjects(cur, draft);
  const hasRpIdChange = changes.some((c) => c.key === "rpId");

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">WebAuthn Configuration</h3>
            <div className="card__sub">RP가 ceremony 요청 시 사용하는 파라미터. 변경은 즉시 다음 ceremony부터 적용됩니다.</div>
          </div>
          <div className="row">
            {dirty && <span className="badge badge--warning badge--dot">변경 사항 {changes.length}건</span>}
            <button className="btn btn--sm" disabled={!dirty} onClick={() => setDraft({ ...cur })}>되돌리기</button>
            <button className="btn btn--primary btn--sm" disabled={!dirty} onClick={() => setShowDiff(true)}>저장…</button>
          </div>
        </div>

        <div className="card__body">
          <div className="grid-2" style={{ gap: 24 }}>
            <div className="stack-3">
              <Field label="rpId" hint="Relying Party의 hostname. 변경 시 기존 credential이 무효화될 수 있습니다.">
                <input className="input mono" value={draft.rpId} onChange={(e) => setDraft({ ...draft, rpId: e.target.value })} />
              </Field>
              <Field label="rpName" hint="UA 선택 화면에 표시되는 표시 이름.">
                <input className="input" value={draft.rpName} onChange={(e) => setDraft({ ...draft, rpName: e.target.value })} />
              </Field>
              <Field label="timeoutMs" hint="ceremony 타임아웃 (밀리초). 권장 60000–120000.">
                <input className="input mono" type="number" value={draft.timeoutMs} onChange={(e) => setDraft({ ...draft, timeoutMs: parseInt(e.target.value || "0", 10) })} />
              </Field>
            </div>
            <div className="stack-3">
              <Field label="origins" hint="ceremony가 시작될 수 있는 origin. 정확히 일치해야 함.">
                <div style={{ display: "flex", gap: 6, flexWrap: "wrap", padding: "8px 8px", border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)", minHeight: 38 }}>
                  {draft.origins.map((o) => (
                    <span key={o} className="chip mono" style={{ fontSize: 11 }}>
                      {o}
                      <button className="chip__x" onClick={() => removeOrigin(o)}><Icons.X size={11} /></button>
                    </span>
                  ))}
                  <input
                    placeholder="https://… 입력 후 Enter"
                    style={{ border: 0, outline: "none", fontSize: 12, padding: "2px 4px", flex: 1, minWidth: 160, background: "transparent", color: "var(--text)" }}
                    value={originInput}
                    onChange={(e) => setOriginInput(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addOrigin())}
                  />
                </div>
              </Field>
              <Field label="userVerification" hint="UV flag. REQUIRED 권장 — PIN/biometric 강제.">
                <Segmented value={draft.userVerification} onChange={(v) => setDraft({ ...draft, userVerification: v })} options={["REQUIRED", "PREFERRED", "DISCOURAGED"]} />
              </Field>
              <Field label="attestationConveyance" hint="attestation 객체 전달 모드.">
                <Segmented value={draft.attestationConveyance} onChange={(v) => setDraft({ ...draft, attestationConveyance: v })} options={["NONE", "INDIRECT", "DIRECT", "ENTERPRISE"]} />
              </Field>
            </div>
          </div>
        </div>
      </div>

      {hasRpIdChange && (
        <div className="card" style={{ borderColor: "var(--danger)", background: "var(--danger-soft)" }}>
          <div className="card__body" style={{ display: "flex", gap: 10, padding: 16 }}>
            <Icons.Alert size={18} />
            <div>
              <div style={{ fontWeight: 600, color: "var(--danger)" }}>rpId가 변경됩니다.</div>
              <div style={{ fontSize: 13, marginTop: 4, color: "var(--text)" }}>이 tenant의 모든 기존 credential ({fmt(tenant.credentials)}건)이 다음 ceremony부터 인증에 실패할 수 있습니다. 사용자에게 재등록 안내가 필요합니다.</div>
            </div>
          </div>
        </div>
      )}

      <DiffDialog open={showDiff} onClose={() => setShowDiff(false)} changes={changes} onConfirm={() => {
        setShowDiff(false);
        toast({ kind: "ok", title: "WebAuthn config 저장됨", message: `${changes.length}개 필드가 업데이트되었습니다.`, traceId: "tr_4f2c1b" });
      }} />
    </div>
  );
}

function Field({ label, hint, children }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

function Segmented({ value, onChange, options }) {
  return (
    <div style={{ display: "inline-flex", padding: 3, background: "var(--surface-3)", borderRadius: 8, border: "1px solid var(--border)" }}>
      {options.map((o) => (
        <button
          key={o}
          onClick={() => onChange(o)}
          style={{
            padding: "4px 10px",
            border: 0, borderRadius: 6,
            background: value === o ? "var(--surface)" : "transparent",
            color: value === o ? "var(--text)" : "var(--text-mute)",
            fontWeight: value === o ? 600 : 500,
            fontSize: 11, letterSpacing: "0.02em",
            boxShadow: value === o ? "var(--shadow-sm)" : "none",
            cursor: "pointer", fontFamily: "var(--mono)",
          }}
        >
          {o}
        </button>
      ))}
    </div>
  );
}

function diffObjects(a, b) {
  const out = [];
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  keys.forEach((k) => {
    if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) out.push({ key: k, from: a[k], to: b[k] });
  });
  return out;
}

function DiffDialog({ open, onClose, changes, onConfirm }) {
  return (
    <Dialog open={open} onClose={onClose} title="변경 사항 확인" sub="저장하면 다음 ceremony부터 라이브 RP에 즉시 적용됩니다." wide
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" onClick={onConfirm}>{changes.length}개 변경 사항 저장</button>
      </>}
    >
      <div style={{ border: "1px solid var(--border)", borderRadius: 8, overflow: "hidden" }}>
        {changes.map((c, i) => <DiffRow key={c.key} c={c} last={i === changes.length - 1} />)}
        {changes.length === 0 && <div style={{ padding: 18, fontSize: 13, color: "var(--text-mute)" }}>변경 사항이 없습니다.</div>}
      </div>
      <div style={{ marginTop: 12, padding: 10, background: "var(--warning-soft)", color: "var(--warning)", borderRadius: 6, fontSize: 12, display: "flex", gap: 8 }}>
        <Icons.Alert size={14} />
        <span>이 변경은 즉시 라이브 RP에 영향을 줍니다. 변경 사항은 audit log에 <code style={{ background: "rgba(0,0,0,0.05)", padding: "0 4px", borderRadius: 3 }}>WEBAUTHN_CONFIG_UPDATED</code>로 기록됩니다.</span>
      </div>
    </Dialog>
  );
}

function DiffRow({ c, last }) {
  function renderVal(v) {
    if (Array.isArray(v)) return v.map((x, i) => <div key={i} className="mono" style={{ fontSize: 11 }}>{x}</div>);
    if (typeof v === "object" && v !== null) return <code style={{ fontSize: 11 }}>{JSON.stringify(v)}</code>;
    return <span className="mono" style={{ fontSize: 12 }}>{String(v)}</span>;
  }
  // origin-array diff: enumerate added / removed lines
  if (Array.isArray(c.from) && Array.isArray(c.to)) {
    const added = c.to.filter((x) => !c.from.includes(x));
    const removed = c.from.filter((x) => !c.to.includes(x));
    return (
      <div style={{ padding: 14, borderBottom: last ? 0 : "1px solid var(--border)" }}>
        <div style={{ fontFamily: "var(--mono)", fontSize: 12, fontWeight: 600, color: "var(--text)" }}>{c.key}</div>
        <div style={{ marginTop: 8, display: "grid", gap: 4 }}>
          {removed.map((x) => <DiffLine key={"-"+x} sign="-" value={x} />)}
          {added.map((x) => <DiffLine key={"+"+x} sign="+" value={x} />)}
        </div>
      </div>
    );
  }
  return (
    <div style={{ padding: 14, borderBottom: last ? 0 : "1px solid var(--border)" }}>
      <div style={{ fontFamily: "var(--mono)", fontSize: 12, fontWeight: 600 }}>{c.key}</div>
      <div style={{ marginTop: 8, display: "grid", gap: 4 }}>
        <DiffLine sign="-" value={c.from} />
        <DiffLine sign="+" value={c.to} />
      </div>
    </div>
  );
}

function DiffLine({ sign, value }) {
  const isAdd = sign === "+";
  return (
    <div style={{
      display: "flex", gap: 8,
      padding: "4px 10px",
      background: isAdd ? "color-mix(in oklab, var(--success-soft) 70%, transparent)" : "color-mix(in oklab, var(--danger-soft) 70%, transparent)",
      borderRadius: 4,
      fontFamily: "var(--mono)", fontSize: 12,
      color: isAdd ? "var(--success)" : "var(--danger)",
    }}>
      <span style={{ width: 10, fontWeight: 700 }}>{sign}</span>
      <span style={{ color: "var(--text)" }}>{String(value)}</span>
    </div>
  );
}

// ===================== AAGUID Policy =====================
function AaguidPolicyTab({ tenant }) {
  const cur = window.MOCK.ATTESTATION_POLICY;
  const NAMES = window.MOCK.AAGUID_NAMES;
  const [draft, setDraft] = useState2({ ...cur, allowed: [...cur.allowed], denied: [...cur.denied] });
  const [aaguidInput, setAaguidInput] = useState2("");
  const toast = useToast();
  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const inputValid = uuidRe.test(aaguidInput);
  const dirty = JSON.stringify(cur) !== JSON.stringify(draft);

  const list = draft.mode === "ALLOWLIST" ? draft.allowed : draft.mode === "DENYLIST" ? draft.denied : [];
  const setList = (next) => setDraft((d) => ({ ...d, [d.mode === "ALLOWLIST" ? "allowed" : "denied"]: next }));

  function add() {
    if (!inputValid) return;
    if (list.includes(aaguidInput.toLowerCase())) return;
    setList([...list, aaguidInput.toLowerCase()]);
    setAaguidInput("");
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">AAGUID Attestation Policy</h3>
            <div className="card__sub">authenticator 모델별 허용/차단. ANY는 RP가 처음 온보딩할 때만 사용 권장.</div>
          </div>
          <div className="row">
            <button className="btn btn--sm" disabled={!dirty} onClick={() => setDraft({ ...cur, allowed: [...cur.allowed], denied: [...cur.denied] })}>되돌리기</button>
            <button className="btn btn--primary btn--sm" disabled={!dirty} onClick={() => toast({ kind: "ok", title: "AAGUID 정책 저장됨", traceId: "tr_9a0c81" })}>저장</button>
          </div>
        </div>

        <div className="card__body stack-4">
          <Field label="mode">
            <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 8, maxWidth: 540 }}>
              {[
                { v: "ANY", t: "ANY", d: "모든 authenticator 허용" },
                { v: "ALLOWLIST", t: "ALLOWLIST", d: "지정된 AAGUID만 허용" },
                { v: "DENYLIST", t: "DENYLIST", d: "지정된 AAGUID만 차단" },
              ].map((o) => (
                <button key={o.v} onClick={() => setDraft({ ...draft, mode: o.v })}
                  style={{
                    padding: 10, textAlign: "left",
                    border: `1px solid ${draft.mode === o.v ? "var(--accent)" : "var(--border)"}`,
                    background: draft.mode === o.v ? "var(--accent-soft)" : "var(--surface)",
                    color: draft.mode === o.v ? "var(--accent)" : "var(--text)",
                    borderRadius: 8, cursor: "pointer",
                  }}>
                  <div className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{o.t}</div>
                  <div style={{ fontSize: 11, color: "var(--text-mute)", marginTop: 4 }}>{o.d}</div>
                </button>
              ))}
            </div>
          </Field>

          {draft.mode !== "ANY" && (
            <Field label={draft.mode === "ALLOWLIST" ? "허용된 AAGUID" : "차단된 AAGUID"} hint="UUID v4 형식. FIDO MDS 매핑된 이름이 옆에 표시됩니다.">
              <div style={{ display: "flex", gap: 6, marginBottom: 8 }}>
                <input className="input mono" placeholder="ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4" value={aaguidInput} onChange={(e) => setAaguidInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && add()} />
                <button className="btn btn--primary btn--sm" disabled={!inputValid} onClick={add}><Icons.Plus size={12} /> 추가</button>
              </div>
              <div style={{ border: "1px solid var(--border)", borderRadius: 8, overflow: "hidden" }}>
                {list.length === 0 ? (
                  <div style={{ padding: 14, fontSize: 12, color: "var(--text-mute)" }}>비어 있음 — 이 상태에서는 {draft.mode === "ALLOWLIST" ? "모든 ceremony가 차단" : "모든 ceremony가 허용"}됩니다.</div>
                ) : list.map((u, i) => (
                  <div key={u} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "8px 12px", borderBottom: i === list.length - 1 ? 0 : "1px solid var(--border)" }}>
                    <div className="row">
                      <Icons.Fingerprint size={14} />
                      <span className="mono" style={{ fontSize: 12 }}>{u}</span>
                      {NAMES[u] && <span className="badge badge--accent">{NAMES[u]}</span>}
                    </div>
                    <button className="btn btn--ghost btn--xs" onClick={() => setList(list.filter((x) => x !== u))}><Icons.X size={12} /></button>
                  </div>
                ))}
              </div>
            </Field>
          )}

          <Field label="MDS Strict 모드" hint="FIDO Metadata Service의 trust anchor만 허용. MDS 서비스 다운 시 모든 등록이 차단됩니다.">
            <Toggle on={draft.mdsStrict} onChange={(v) => setDraft({ ...draft, mdsStrict: v })} label={draft.mdsStrict ? "MDS strict ON" : "MDS strict OFF"} />
          </Field>
        </div>
      </div>
    </div>
  );
}

function Toggle({ on, onChange, label }) {
  return (
    <button onClick={() => onChange(!on)} style={{ display: "inline-flex", alignItems: "center", gap: 8, border: 0, background: "transparent", padding: 0, cursor: "pointer" }}>
      <span style={{
        position: "relative", width: 36, height: 20, borderRadius: 999,
        background: on ? "var(--accent)" : "var(--border-strong)",
        transition: "background 120ms ease", display: "inline-block",
      }}>
        <span style={{
          position: "absolute", top: 2, left: on ? 18 : 2,
          width: 16, height: 16, borderRadius: 999, background: "white",
          boxShadow: "0 1px 3px rgba(0,0,0,0.2)", transition: "left 120ms ease",
        }} />
      </span>
      <span style={{ fontSize: 13, color: "var(--text)" }}>{label}</span>
    </button>
  );
}

Object.assign(window, { TenantDetailPage, TenantHeader, TenantTabs, TenantOverview, WebauthnConfigTab, AaguidPolicyTab, Field, Segmented, Toggle, diffObjects, DiffDialog });
