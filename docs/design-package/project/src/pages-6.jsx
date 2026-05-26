/* global React, Icons, Dialog, useToast, StatusBadge, MetricCard, Field, Toggle, ChipTab, timeAgo, fmtDateTime, tail, fmt */
const { useState: useState6 } = React;

// ===== Settings — Admin users + MDS + 시스템 =====
function SettingsPage() {
  const [tab, setTab] = useState6("admins");
  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">설정</h1>
          <div className="page__sub">콘솔 운영자, MDS 신뢰 anchor, 시스템 상태.</div>
        </div>
      </div>

      <div className="tabs">
        <button className={`tabs__btn ${tab==="admins"?"tabs__btn--active":""}`} onClick={() => setTab("admins")}>Admin 사용자</button>
        <button className={`tabs__btn ${tab==="mds"?"tabs__btn--active":""}`} onClick={() => setTab("mds")}>MDS Status</button>
        <button className={`tabs__btn ${tab==="system"?"tabs__btn--active":""}`} onClick={() => setTab("system")}>시스템</button>
        <button className={`tabs__btn ${tab==="security"?"tabs__btn--active":""}`} onClick={() => setTab("security")}>보안 정책</button>
      </div>

      {tab === "admins" && <AdminUsersTab />}
      {tab === "mds" && <MdsStatusTab />}
      {tab === "system" && <SystemInfoTab />}
      {tab === "security" && <SecurityPolicyTab />}
    </div>
  );
}

const ADMIN_LIST = [
  { id: "adm_jhyun_01", email: "jhyun@crosscert.com", displayName: "정 운영자", role: "PLATFORM_OPERATOR", tenantId: null, status: "ACTIVE", createdAt: "2025-04-02T09:00:00Z", lastLoginAt: "2026-05-16T07:01:32Z", mfa: true },
  { id: "adm_park_op", email: "park.ops@crosscert.com", displayName: "박 운영", role: "PLATFORM_OPERATOR", tenantId: null, status: "ACTIVE", createdAt: "2025-06-11T13:24:11Z", lastLoginAt: "2026-05-15T18:11:00Z", mfa: true },
  { id: "adm_kim_iam", email: "kim.iam@acme.example.com", displayName: "김 IAM담당", role: "RP_ADMIN", tenantId: "tnt_01HBXAC8FE", status: "ACTIVE", createdAt: "2026-01-11T09:00:00Z", lastLoginAt: "2026-05-16T06:30:18Z", mfa: true },
  { id: "adm_lee_globex", email: "y.lee@globex-fin.com", displayName: "이 영민", role: "RP_ADMIN", tenantId: "tnt_01HBKR3QM2", status: "ACTIVE", createdAt: "2025-10-08T14:22:55Z", lastLoginAt: "2026-05-16T01:55:09Z", mfa: false },
  { id: "adm_choi_hooli", email: "j.choi@hooli-pay.com", displayName: "최 진우", role: "RP_ADMIN", tenantId: "tnt_01HXC74WPM", status: "SUSPENDED", createdAt: "2025-08-30T11:11:00Z", lastLoginAt: "2026-04-22T10:14:55Z", mfa: true },
];

function AdminUsersTab() {
  const [admins, setAdmins] = useState6(ADMIN_LIST);
  const [showNew, setShowNew] = useState6(false);
  const [editing, setEditing] = useState6(null);
  const toast = useToast();

  function create(payload) {
    const id = "adm_" + Math.random().toString(36).slice(2, 9);
    const newAdmin = {
      id,
      email: payload.email,
      displayName: payload.displayName,
      role: payload.role,
      tenantId: payload.role === "RP_ADMIN" ? payload.tenantId : null,
      status: "PENDING",
      createdAt: new Date().toISOString(),
      lastLoginAt: null,
      mfa: payload.requireMfa,
    };
    setAdmins([newAdmin, ...admins]);
    setShowNew(false);
    toast({
      kind: "ok",
      title: "운영자가 생성되었습니다.",
      message: `${payload.displayName} (${payload.email}) · 초대 메일 발송 완료`,
      traceId: "tr_adm_" + id.slice(-6),
    });
  }

  function toggleStatus(adm) {
    const next = adm.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    setAdmins(admins.map((a) => a.id === adm.id ? { ...a, status: next } : a));
    toast({
      kind: next === "SUSPENDED" ? "warn" : "ok",
      title: next === "SUSPENDED" ? "운영자가 정지되었습니다." : "운영자가 재활성화되었습니다.",
      message: adm.email,
    });
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">콘솔 운영자</h3>
            <div className="card__sub">{admins.length}명 · 활성 {admins.filter((a) => a.status === "ACTIVE").length}명 · 대기 {admins.filter((a) => a.status === "PENDING").length}명</div>
          </div>
          <button className="btn btn--primary btn--sm" onClick={() => setShowNew(true)}>
            <Icons.Plus size={12} /> 운영자 추가
          </button>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>운영자</th>
              <th>Role</th>
              <th>Tenant</th>
              <th>MFA</th>
              <th>마지막 로그인</th>
              <th>Status</th>
              <th style={{ textAlign: "right" }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {admins.map((a) => (
              <tr key={a.id} style={{ opacity: a.status === "SUSPENDED" ? 0.55 : 1 }}>
                <td>
                  <div className="row">
                    <div style={{ width: 28, height: 28, borderRadius: 999, background: a.role === "PLATFORM_OPERATOR" ? "var(--violet-soft)" : "var(--info-soft)", color: a.role === "PLATFORM_OPERATOR" ? "var(--violet)" : "var(--info)", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 11, flex: "none" }}>
                      {a.displayName.slice(0, 1)}
                    </div>
                    <div className="stack-1">
                      <div style={{ fontWeight: 600, fontSize: 13 }}>{a.displayName}</div>
                      <div className="muted mono" style={{ fontSize: 11 }}>{a.email}</div>
                    </div>
                  </div>
                </td>
                <td><span className={`badge ${a.role==="PLATFORM_OPERATOR"?"badge--violet":"badge--info"}`}>{a.role}</span></td>
                <td>{a.tenantId ? <span className="mono" style={{ fontSize: 12 }}>{tail(a.tenantId, 10)}</span> : <span className="faint">—</span>}</td>
                <td>{a.mfa ? <span className="badge badge--success" style={{ fontSize: 10 }}><Icons.Check size={10} /> ON</span> : <span className="badge badge--warning" style={{ fontSize: 10 }}><Icons.Alert size={10} /> OFF</span>}</td>
                <td>{a.lastLoginAt ? <span className="muted">{timeAgo(a.lastLoginAt)}</span> : <span className="faint">미접속</span>}</td>
                <td><StatusBadge status={a.status} /></td>
                <td style={{ textAlign: "right" }}>
                  <div className="row" style={{ justifyContent: "flex-end", gap: 4 }}>
                    {a.status === "PENDING" && <button className="btn btn--xs" title="초대 메일 재발송"><Icons.Refresh size={11} /></button>}
                    <button className="btn btn--xs" onClick={() => toggleStatus(a)} style={{ color: a.status === "ACTIVE" ? "var(--warning)" : "var(--success)" }}>
                      {a.status === "ACTIVE" ? "정지" : "활성화"}
                    </button>
                    <button className="btn btn--ghost btn--xs"><Icons.Dots size={14} /></button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <NewAdminDialog open={showNew} onClose={() => setShowNew(false)} onCreate={create} existingEmails={admins.map((a) => a.email)} />
    </div>
  );
}

function NewAdminDialog({ open, onClose, onCreate, existingEmails }) {
  const tenants = window.MOCK.TENANTS;
  const [email, setEmail] = useState6("");
  const [displayName, setDisplayName] = useState6("");
  const [role, setRole] = useState6("RP_ADMIN");
  const [tenantId, setTenantId] = useState6(tenants[0]?.id || "");
  const [requireMfa, setRequireMfa] = useState6(true);
  const [touched, setTouched] = useState6(false);

  React.useEffect(() => {
    if (!open) { setEmail(""); setDisplayName(""); setRole("RP_ADMIN"); setTenantId(tenants[0]?.id || ""); setRequireMfa(true); setTouched(false); }
  }, [open]);

  const emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  const emailValid = emailRe.test(email);
  const emailDup = existingEmails.includes(email.toLowerCase());
  const nameValid = displayName.trim().length >= 2;
  const formValid = emailValid && !emailDup && nameValid && (role === "PLATFORM_OPERATOR" || tenantId);

  function submit() {
    setTouched(true);
    if (!formValid) return;
    onCreate({ email: email.toLowerCase(), displayName: displayName.trim(), role, tenantId, requireMfa });
  }

  if (!open) return null;
  const selectedTenant = tenants.find((t) => t.id === tenantId);

  return (
    <Dialog open onClose={onClose} wide
      title="운영자 추가"
      sub="새 운영자에게 초대 메일이 발송됩니다. 24시간 안에 비밀번호를 설정하지 않으면 만료됩니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" disabled={!formValid} onClick={submit}>운영자 생성 + 초대 발송</button>
      </>}
    >
      <div className="stack-3">
        <div className="grid-2">
          <Field label="이메일" hint={touched && emailDup ? <span style={{ color: "var(--danger)" }}>이미 등록된 이메일입니다</span> : touched && email && !emailValid ? <span style={{ color: "var(--danger)" }}>유효한 이메일 형식이 아닙니다</span> : "로그인 ID로 사용됩니다."}>
            <input className="input" type="email" placeholder="user@example.com" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
          </Field>
          <Field label="표시 이름" hint="콘솔 헤더와 audit log에 표시됩니다.">
            <input className="input" placeholder="예: 김 보안" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </Field>
        </div>

        <Field label="Role">
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8 }}>
            {[
              { v: "PLATFORM_OPERATOR", t: "Platform Operator", d: "모든 tenant에 대해 cross-tenant 운영 가능. Crosscert 사내용." },
              { v: "RP_ADMIN", t: "RP Admin", d: "한 tenant 안에서만 모든 권한. RP 회사의 IAM 담당자용." },
            ].map((opt) => (
              <button
                key={opt.v}
                type="button"
                onClick={() => setRole(opt.v)}
                style={{
                  padding: "10px 12px",
                  borderRadius: 8,
                  border: `1px solid ${role === opt.v ? "var(--accent)" : "var(--border)"}`,
                  background: role === opt.v ? "var(--accent-soft)" : "var(--surface)",
                  color: role === opt.v ? "var(--accent)" : "var(--text)",
                  cursor: "pointer", textAlign: "left",
                }}
              >
                <div className="row" style={{ gap: 6 }}>
                  <Icons.Shield size={13} />
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{opt.t}</div>
                </div>
                <div style={{ fontSize: 11, color: role === opt.v ? "color-mix(in oklab, var(--accent) 70%, var(--text))" : "var(--text-mute)", marginTop: 4, lineHeight: 1.5 }}>{opt.d}</div>
              </button>
            ))}
          </div>
        </Field>

        {role === "RP_ADMIN" && (
          <Field label="할당 Tenant" hint="이 운영자는 선택한 tenant만 접근 가능합니다. 생성 후 변경하려면 새 운영자를 만들어야 합니다.">
            <select className="input" value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
              {tenants.filter((t) => t.status === "ACTIVE").map((t) => (
                <option key={t.id} value={t.id}>{t.name} · {t.slug}</option>
              ))}
            </select>
            {selectedTenant && (
              <div style={{ marginTop: 8, padding: "8px 12px", background: "var(--surface-3)", borderRadius: 6, fontSize: 12, color: "var(--text-soft)", display: "flex", alignItems: "center", gap: 10 }}>
                <div style={{ width: 22, height: 22, borderRadius: 5, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 10 }}>{selectedTenant.name.slice(0, 1)}</div>
                <span>로그인 즉시 <strong>{selectedTenant.name}</strong>의 상세 페이지로 자동 라우팅됩니다.</span>
              </div>
            )}
          </Field>
        )}

        <Field label="보안">
          <label style={{ display: "flex", gap: 10, padding: "10px 12px", background: requireMfa ? "var(--success-soft)" : "var(--surface-3)", borderRadius: 8, alignItems: "flex-start", cursor: "pointer", border: `1px solid ${requireMfa ? "color-mix(in oklab, var(--success) 25%, transparent)" : "var(--border)"}` }}>
            <input type="checkbox" checked={requireMfa} onChange={(e) => setRequireMfa(e.target.checked)} style={{ marginTop: 2 }} />
            <div style={{ fontSize: 13 }}>
              <div style={{ fontWeight: 600, color: requireMfa ? "var(--success)" : "var(--text)" }}>MFA 필수 (권장)</div>
              <div style={{ color: "var(--text-mute)", marginTop: 2, fontSize: 12 }}>최초 로그인 시 TOTP authenticator 등록이 강제됩니다. 보안 정책에서 일괄 변경 가능.</div>
            </div>
          </label>
        </Field>

        <div style={{ padding: "10px 12px", background: "var(--info-soft)", color: "var(--info)", borderRadius: 6, fontSize: 12, display: "flex", gap: 8 }}>
          <Icons.Info size={14} />
          <div>
            <div style={{ fontWeight: 600 }}>다음 단계</div>
            <div style={{ marginTop: 2, color: "var(--text-soft)" }}>
              생성 즉시 <code style={{ background: "rgba(0,0,0,0.06)", padding: "1px 4px", borderRadius: 3, fontFamily: "var(--mono)" }}>{email || "user@example.com"}</code>로 초대 메일이 발송되며, 24시간 내에 비밀번호 설정 + MFA 등록이 필요합니다. 모든 단계는 audit log에 기록됩니다.
            </div>
          </div>
        </div>
      </div>
    </Dialog>
  );
}

function MdsStatusTab() {
  const lastFetch = "2026-05-16T03:00:00Z";
  const trustAnchors = 287;
  return (
    <div className="stack-4">
      <div className="grid-3">
        <MetricCard label="MDS BLOB 버전" value="9 · 2026-05" sub="FIDO Alliance 최신본" />
        <MetricCard label="Trust anchors" value={trustAnchors} sub="활성 authenticator 모델" />
        <MetricCard label="마지막 동기화" value={timeAgo(lastFetch)} sub={fmtDateTime(lastFetch)} />
      </div>

      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">MDS 동기화 상태</h3>
            <div className="card__sub">FIDO Metadata Service에서 authenticator 모델 정보를 매일 03:00 KST에 자동 갱신.</div>
          </div>
          <button className="btn btn--primary btn--sm"><Icons.Refresh size={12} /> 즉시 갱신</button>
        </div>
        <div className="card__body stack-3">
          <KvLine k="endpoint" v={<code className="mono" style={{ background: "var(--surface-3)", padding: "2px 8px", borderRadius: 4, fontSize: 12 }}>https://mds3.fidoalliance.org/</code>} />
          <KvLine k="next 갱신 예정" v="2026-05-17 03:00 KST · 19시간 28분 후" />
          <KvLine k="동기화 성공률 (30d)" v={<><span style={{ fontWeight: 600 }}>30 / 30</span> · <span className="badge badge--success">100%</span></>} />
          <KvLine k="현재 사용 중인 신뢰 모드" v={<span className="badge badge--info">MDS_STRICT_OPTIONAL</span>} />
        </div>
      </div>

      <div className="card">
        <div className="card__head">
          <h3 className="card__title">최근 동기화 이력</h3>
        </div>
        <table className="table">
          <thead><tr><th>시각</th><th>버전</th><th>변경</th><th>Status</th><th>응답 시간</th></tr></thead>
          <tbody>
            {[
              { ts: "2026-05-16T03:00:00Z", ver: "9 · 2026-05", changes: "+2 / -0 / ~1", ok: true, ms: 412 },
              { ts: "2026-05-15T03:00:00Z", ver: "9 · 2026-05", changes: "변경 없음", ok: true, ms: 388 },
              { ts: "2026-05-14T03:00:00Z", ver: "9 · 2026-04", changes: "+1 / -0 / ~0", ok: true, ms: 401 },
              { ts: "2026-05-13T03:00:00Z", ver: "9 · 2026-04", changes: "변경 없음", ok: true, ms: 372 },
              { ts: "2026-05-12T03:00:00Z", ver: "9 · 2026-04", changes: "변경 없음", ok: true, ms: 421 },
            ].map((r, i) => (
              <tr key={i}>
                <td><span className="muted">{fmtDateTime(r.ts)}</span></td>
                <td className="mono" style={{ fontSize: 12 }}>{r.ver}</td>
                <td>{r.changes}</td>
                <td>{r.ok ? <span className="badge badge--success badge--dot">OK</span> : <span className="badge badge--danger badge--dot">FAIL</span>}</td>
                <td className="mono muted" style={{ fontSize: 12 }}>{r.ms}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function KvLine({ k, v }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "180px 1fr", gap: 12, alignItems: "center", fontSize: 13, padding: "6px 0" }}>
      <div className="muted" style={{ fontSize: 12 }}>{k}</div>
      <div>{v}</div>
    </div>
  );
}

function SystemInfoTab() {
  return (
    <div className="stack-4">
      <div className="grid-3">
        <MetricCard label="Server 버전" value="v1.0.4" sub="2026-05-14 배포" />
        <MetricCard label="API 응답 (p95)" value="42ms" sub="평균 18ms · p99 89ms" />
        <MetricCard label="Uptime" value="99.97%" sub="30d · 8.6분 incident" />
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card__head"><h3 className="card__title">백엔드 컴포넌트</h3></div>
          <div className="card__body stack-3">
            <ComponentRow name="Crosscert Passkey API" version="v1.0.4" status="OK" instances={4} />
            <ComponentRow name="PostgreSQL" version="16.4" status="OK" instances={3} note="primary + 2 read replica" />
            <ComponentRow name="Redis (cache + pub/sub)" version="7.4" status="OK" instances={3} note="cluster mode" />
            <ComponentRow name="FIDO MDS Sync" version="—" status="OK" instances={1} note="daily 03:00 KST" />
          </div>
        </div>

        <div className="card">
          <div className="card__head"><h3 className="card__title">호스트 정보</h3></div>
          <div className="card__body stack-3">
            <KvLine k="API hostname" v={<code className="mono" style={{ background: "var(--surface-3)", padding: "2px 8px", borderRadius: 4, fontSize: 12 }}>api.passkey.example.com</code>} />
            <KvLine k="Admin console" v={<code className="mono" style={{ background: "var(--surface-3)", padding: "2px 8px", borderRadius: 4, fontSize: 12 }}>admin.passkey.example.com</code>} />
            <KvLine k="region" v="ap-northeast-2 (Seoul)" />
            <KvLine k="환경" v={<span className="badge badge--violet">production</span>} />
            <KvLine k="배포 방식" v="k8s · 1 admin replica · 4 API replica" />
          </div>
        </div>
      </div>
    </div>
  );
}

function ComponentRow({ name, version, status, instances, note }) {
  return (
    <div className="row" style={{ justifyContent: "space-between" }}>
      <div>
        <div style={{ fontWeight: 600, fontSize: 13 }}>{name}</div>
        {note && <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>{note}</div>}
      </div>
      <div className="row">
        <span className="mono muted" style={{ fontSize: 11 }}>{version}</span>
        <span className="mono muted" style={{ fontSize: 11 }}>× {instances}</span>
        <span className="badge badge--success badge--dot">{status}</span>
      </div>
    </div>
  );
}

function SecurityPolicyTab() {
  const [sessionMin, setSessionMin] = useState6(30);
  const [pwMin, setPwMin] = useState6(12);
  const [reqMfa, setReqMfa] = useState6(true);
  const toast = useToast();
  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <h3 className="card__title">세션 & 인증 정책</h3>
        </div>
        <div className="card__body stack-4">
          <Field label="세션 idle timeout (분)" hint="이 시간 동안 활동이 없으면 자동 로그아웃됩니다. PRD REQ-A-5.">
            <input className="input mono" type="number" value={sessionMin} onChange={(e) => setSessionMin(parseInt(e.target.value || "0", 10))} style={{ width: 120 }} />
          </Field>
          <Field label="비밀번호 최소 길이" hint="이 길이 미만의 비밀번호는 거부됩니다. 변경은 다음 로그인부터 적용.">
            <input className="input mono" type="number" value={pwMin} onChange={(e) => setPwMin(parseInt(e.target.value || "0", 10))} style={{ width: 120 }} />
          </Field>
          <Field label="MFA 필수">
            <Toggle on={reqMfa} onChange={setReqMfa} label={reqMfa ? "모든 운영자에게 MFA 필수" : "MFA 선택"} />
          </Field>
          <Field label="CORS Allowlist">
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap", padding: "8px 10px", border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface)" }}>
              <span className="chip mono" style={{ fontSize: 11 }}>https://admin.passkey.example.com<button className="chip__x"><Icons.X size={11} /></button></span>
              <input placeholder="https://… 추가" style={{ border: 0, outline: "none", fontSize: 12, padding: "2px 4px", flex: 1, minWidth: 200, background: "transparent", color: "var(--text)" }} />
            </div>
          </Field>
        </div>
        <div className="card__head" style={{ borderTop: "1px solid var(--border)", borderBottom: 0, justifyContent: "flex-end" }}>
          <button className="btn">취소</button>
          <button className="btn btn--primary" onClick={() => toast({ kind: "ok", title: "보안 정책 저장됨", traceId: "tr_sec_001" })}>저장</button>
        </div>
      </div>
    </div>
  );
}

window.SettingsPage = SettingsPage;
