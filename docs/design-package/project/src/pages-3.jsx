/* global React, Icons, Dialog, useToast, EmptyState, StatusBadge, CopyBtn, MetricCard, Field, Toggle, timeAgo, fmtDateTime, tail, fmt */
const { useState: useState3, useMemo: useMemo3, useEffect: useEffect3 } = React;

// ===================== API Keys =====================
function ApiKeysTab({ tenant }) {
  const initial = (window.MOCK.API_KEYS[tenant.id] || window.MOCK.API_KEYS["tnt_01HBXAC8FE"]);
  const [keys, setKeys] = useState3(initial);
  const [showNew, setShowNew] = useState3(false);
  const [issued, setIssued] = useState3(null);
  const [revoking, setRevoking] = useState3(null);
  const toast = useToast();

  function issue(name) {
    const id = "ak_" + Math.random().toString(36).slice(2, 8);
    const prefix = "pk_" + Math.random().toString(36).slice(2, 10);
    const plaintext = `${prefix}.${Array(48).fill(0).map(() => "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(Math.floor(Math.random() * 62))).join("")}`;
    const k = { id, prefix, name, status: "ACTIVE", createdAt: new Date().toISOString(), lastUsedAt: null };
    setKeys([k, ...keys]);
    setShowNew(false);
    setIssued({ ...k, plaintext });
  }

  function revoke(k) {
    setKeys(keys.map((x) => x.id === k.id ? { ...x, status: "REVOKED" } : x));
    toast({ kind: "warn", title: "API key가 회수되었습니다.", message: `${k.prefix} · ${k.name}`, traceId: "tr_8c9e21" });
    setRevoking(null);
  }

  const activeCount = keys.filter((k) => k.status === "ACTIVE").length;

  return (
    <div className="stack-4">
      <div className="grid-3">
        <MetricCard label="총 API Key" value={keys.length} sub={`활성 ${activeCount} · 회수 ${keys.length - activeCount}`} />
        <MetricCard label="최근 발급" value="1일 전" sub={`production · ${tail(keys[0]?.id || "—", 6)}`} />
        <MetricCard label="권장 rotation" value="90일" sub="다음 키 회전: 73일 후" />
      </div>

      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">API Keys</h3>
            <div className="card__sub">RP 백엔드에서 Crosscert Passkey API 호출 시 사용. plaintext는 발급 시 1회만 노출됩니다.</div>
          </div>
          <button className="btn btn--primary btn--sm" onClick={() => setShowNew(true)}><Icons.Plus size={12} /> 새 키 발급</button>
        </div>

        <table className="table">
          <thead>
            <tr>
              <th>Prefix</th>
              <th>이름</th>
              <th>Status</th>
              <th>마지막 사용</th>
              <th>생성</th>
              <th style={{ textAlign: "right" }}>액션</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id} style={{ opacity: k.status === "REVOKED" ? 0.55 : 1 }}>
                <td>
                  <div className="row">
                    <Icons.Key size={13} />
                    <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{k.prefix}<span className="faint">.•••••</span></span>
                  </div>
                </td>
                <td>{k.name}</td>
                <td><StatusBadge status={k.status} /></td>
                <td>{k.lastUsedAt ? <span className="muted">{timeAgo(k.lastUsedAt)}</span> : <span className="faint">미사용</span>}</td>
                <td><span className="muted">{fmtDateTime(k.createdAt)}</span></td>
                <td style={{ textAlign: "right" }}>
                  {k.status === "ACTIVE" && (
                    <button className="btn btn--xs" onClick={() => setRevoking(k)} style={{ color: "var(--danger)", borderColor: "color-mix(in oklab, var(--danger) 30%, var(--border))" }}>
                      <Icons.Trash size={12} /> 회수
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <NewKeyDialog open={showNew} onClose={() => setShowNew(false)} onIssue={issue} />
      <IssuedKeyModal issued={issued} onClose={() => { setIssued(null); }} />
      <RevokeKeyDialog k={revoking} onClose={() => setRevoking(null)} onConfirm={revoke} />
    </div>
  );
}

function NewKeyDialog({ open, onClose, onIssue }) {
  const [name, setName] = useState3("");
  function submit() { if (!name) return; onIssue(name); setName(""); }
  return (
    <Dialog open={open} onClose={onClose} title="새 API key 발급"
      sub="발급 후 plaintext는 단 한 번만 노출됩니다. 안전한 장소에 즉시 보관하세요."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--primary" disabled={!name} onClick={submit}>발급</button>
      </>}
    >
      <Field label="용도 (이름)" hint="배포 환경이나 용도를 짧게. 예: production, staging, mobile-app">
        <input autoFocus className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="production" />
      </Field>
      <div style={{ marginTop: 12, padding: 10, background: "var(--info-soft)", color: "var(--info)", borderRadius: 6, fontSize: 12, display: "flex", gap: 8 }}>
        <Icons.Info size={14} />
        <span>발급된 key는 Crosscert이 평문을 보관하지 않습니다. 분실 시 회수 후 재발급만 가능합니다.</span>
      </div>
    </Dialog>
  );
}

function IssuedKeyModal({ issued, onClose }) {
  const [copied, setCopied] = useState3(false);
  const [checked, setChecked] = useState3(false);
  useEffect3(() => { if (issued) { setCopied(false); setChecked(false); } }, [issued]);
  if (!issued) return null;
  return (
    <Dialog open={true} onClose={() => { /* enforce */ }} closeOnScrim={false} wide
      title={<span style={{ display: "flex", alignItems: "center", gap: 8 }}><Icons.Alert size={18} /> 새 API key가 발급되었습니다 — 지금만 표시됩니다</span>}
      sub="이 창을 닫으면 plaintext는 영구히 사라집니다. 절대 다시 표시되지 않습니다."
      footer={<>
        <div style={{ flex: 1, fontSize: 12, color: "var(--text-mute)", display: "flex", alignItems: "center", gap: 8 }}>
          <Icons.Lock size={13} /> server는 plaintext의 해시만 저장합니다.
        </div>
        <button className="btn btn--primary" disabled={!checked} onClick={onClose}>{checked ? "닫기 (영구 소실)" : "체크 필요"}</button>
      </>}
    >
      <div className="stack-3">
        <div style={{ display: "flex", gap: 12, alignItems: "flex-start" }}>
          <span className="badge badge--success">발급 완료</span>
          <div style={{ flex: 1 }}>
            <div className="muted" style={{ fontSize: 12 }}>이름</div>
            <div style={{ fontWeight: 600, fontSize: 14 }}>{issued.name}</div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 12 }}>prefix</div>
            <div className="mono" style={{ fontSize: 12 }}>{issued.prefix}</div>
          </div>
          <div>
            <div className="muted" style={{ fontSize: 12 }}>id</div>
            <div className="mono" style={{ fontSize: 12 }}>{issued.id}</div>
          </div>
        </div>

        <div>
          <div className="label">plaintext API key</div>
          <div style={{ position: "relative" }}>
            <div style={{
              fontFamily: "var(--mono)", fontSize: 12, lineHeight: 1.5,
              padding: "14px 16px 14px 16px", paddingRight: 96,
              background: "var(--surface-3)", borderRadius: 8,
              border: "1px solid var(--border)",
              wordBreak: "break-all",
              color: "var(--text)",
            }}>
              <span style={{ color: "var(--accent)", fontWeight: 600 }}>{issued.prefix}</span>.<span>{issued.plaintext.split(".")[1]}</span>
            </div>
            <button className="btn btn--primary btn--sm" style={{ position: "absolute", top: 8, right: 8 }} onClick={() => { navigator.clipboard?.writeText(issued.plaintext); setCopied(true); }}>
              {copied ? <><Icons.Check size={12} /> 복사됨</> : <><Icons.Copy size={12} /> 클립보드</>}
            </button>
          </div>
        </div>

        <label style={{ display: "flex", gap: 10, padding: 12, background: checked ? "var(--success-soft)" : "var(--warning-soft)", borderRadius: 8, alignItems: "flex-start", cursor: "pointer", border: `1px solid ${checked ? "color-mix(in oklab, var(--success) 25%, transparent)" : "color-mix(in oklab, var(--warning) 25%, transparent)"}` }}>
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} style={{ marginTop: 2 }} />
          <div style={{ fontSize: 13 }}>
            <div style={{ fontWeight: 600, color: checked ? "var(--success)" : "var(--warning)" }}>안전한 장소에 복사했습니다.</div>
            <div style={{ color: "var(--text-soft)", marginTop: 2, fontSize: 12 }}>1Password, AWS Secrets Manager 등 보안 저장소에 보관하세요. 닫기 후에는 재조회 불가능합니다.</div>
          </div>
        </label>
      </div>
    </Dialog>
  );
}

function RevokeKeyDialog({ k, onClose, onConfirm }) {
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} title="API key를 회수하시겠습니까?"
      sub="회수된 키는 다음 ceremony부터 401을 받습니다. 캐시 만료까지 약 5초 이내에 완전 차단됩니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--danger" onClick={() => onConfirm(k)}>회수</button>
      </>}
    >
      <div style={{ padding: 14, border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface-2)" }}>
        <div style={{ display: "grid", gridTemplateColumns: "100px 1fr", rowGap: 8, fontSize: 13 }}>
          <div className="muted">prefix</div><div className="mono">{k.prefix}</div>
          <div className="muted">이름</div><div>{k.name}</div>
          <div className="muted">생성</div><div className="muted">{fmtDateTime(k.createdAt)}</div>
        </div>
      </div>
      <div style={{ marginTop: 12, padding: 10, background: "var(--danger-soft)", color: "var(--danger)", borderRadius: 6, fontSize: 12, display: "flex", gap: 8 }}>
        <Icons.Alert size={14} />
        <span>이 작업은 되돌릴 수 없습니다. RP 서비스에 새 키가 배포되어 있는지 확인하세요.</span>
      </div>
    </Dialog>
  );
}

// ===================== Credentials =====================
function CredentialsTab({ tenant }) {
  const all = window.MOCK.CREDENTIALS;
  const [creds, setCreds] = useState3(all);
  const [q, setQ] = useState3("");
  const [revoking, setRevoking] = useState3(null);
  const toast = useToast();

  const filtered = useMemo3(() => {
    if (!q) return creds;
    return creds.filter((c) => c.externalUserId.includes(q) || c.nickname.toLowerCase().includes(q.toLowerCase()) || c.credId.includes(q));
  }, [q, creds]);

  function revoke(c) {
    setCreds(creds.map((x) => x.credId === c.credId ? { ...x, status: "REVOKED" } : x));
    toast({ kind: "warn", title: "Credential이 회수되었습니다.", message: `${tail(c.credId, 12)} · ${c.externalUserId}`, traceId: "tr_aa102f" });
    setRevoking(null);
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head" style={{ gap: 10 }}>
          <div className="row" style={{ gap: 10, flex: 1 }}>
            <div style={{ position: "relative", flex: 1, maxWidth: 360 }}>
              <span style={{ position: "absolute", left: 9, top: "50%", transform: "translateY(-50%)", color: "var(--text-mute)" }}><Icons.Search size={13} /></span>
              <input className="input" placeholder="externalUserId · nickname · credentialId 검색" value={q} onChange={(e) => setQ(e.target.value)} style={{ paddingLeft: 28, height: 30 }} />
            </div>
            <button className="btn btn--sm"><Icons.Filter size={12} /> aaguid · status</button>
            <span className="muted" style={{ fontSize: 12 }}>{filtered.length}건</span>
          </div>
          <div className="row">
            <button className="btn btn--sm"><Icons.Download size={12} /> CSV</button>
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>credentialId</th>
              <th>externalUserId</th>
              <th>nickname</th>
              <th>authenticator</th>
              <th>transports</th>
              <th style={{ textAlign: "right" }}>sig.counter</th>
              <th>status</th>
              <th>마지막 사용</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((c) => (
              <tr key={c.credId} style={{ opacity: c.status === "REVOKED" ? 0.55 : 1 }}>
                <td className="mono" style={{ fontSize: 12 }}>{tail(c.credId, 12)}</td>
                <td className="mono" style={{ fontSize: 12 }}>{c.externalUserId}</td>
                <td>{c.nickname}</td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    <Icons.Fingerprint size={12} />
                    <span className="badge badge--accent" style={{ fontSize: 10 }}>{c.aaguidName}</span>
                  </div>
                </td>
                <td>
                  <div style={{ display: "flex", gap: 3 }}>
                    {c.transports.map((t) => <span key={t} className="badge" style={{ fontSize: 10 }}>{t}</span>)}
                  </div>
                </td>
                <td style={{ textAlign: "right" }} className="mono">{c.signCounter}</td>
                <td><StatusBadge status={c.status} /></td>
                <td><span className="muted">{timeAgo(c.lastUsedAt)}</span></td>
                <td>
                  {c.status === "ACTIVE" && (
                    <button className="btn btn--xs" onClick={() => setRevoking(c)} style={{ color: "var(--danger)" }}>
                      <Icons.Trash size={12} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div style={{ padding: "10px 14px", display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: 12, color: "var(--text-mute)", borderTop: "1px solid var(--border)" }}>
          <span>page 1 of 18 · 페이지당 50건</span>
          <div className="row" style={{ gap: 4 }}>
            <button className="btn btn--xs"><Icons.ChevronLeft size={12} /></button>
            <button className="btn btn--xs">1</button>
            <button className="btn btn--xs" style={{ background: "var(--accent)", color: "white", borderColor: "var(--accent)" }}>2</button>
            <button className="btn btn--xs">3</button>
            <button className="btn btn--xs"><Icons.ChevronRight size={12} /></button>
          </div>
        </div>
      </div>

      <RevokeCredentialDialog c={revoking} onClose={() => setRevoking(null)} onConfirm={revoke} />
    </div>
  );
}

function RevokeCredentialDialog({ c, onClose, onConfirm }) {
  const [typed, setTyped] = useState3("");
  useEffect3(() => { if (c) setTyped(""); }, [c]);
  if (!c) return null;
  const required = c.credId.slice(-8);
  const ok = typed === required;
  return (
    <Dialog open onClose={onClose} title="Credential 회수"
      sub="회수된 credential은 다음 ceremony부터 인증에 실패합니다. 사용자는 패스키를 재등록해야 합니다."
      footer={<>
        <button className="btn" onClick={onClose}>취소</button>
        <button className="btn btn--danger" disabled={!ok} onClick={() => onConfirm(c)}>확인 — 회수</button>
      </>}
    >
      <div style={{ padding: 14, border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface-2)" }}>
        <div style={{ display: "grid", gridTemplateColumns: "130px 1fr", rowGap: 8, fontSize: 13 }}>
          <div className="muted">externalUserId</div><div className="mono">{c.externalUserId}</div>
          <div className="muted">nickname</div><div>{c.nickname}</div>
          <div className="muted">authenticator</div><div><span className="badge badge--accent">{c.aaguidName}</span></div>
          <div className="muted">credentialId</div><div className="mono" style={{ fontSize: 11 }}>{c.credId}</div>
          <div className="muted">마지막 사용</div><div className="muted">{fmtDateTime(c.lastUsedAt)}</div>
        </div>
      </div>

      <Field label={<>확인을 위해 credentialId의 마지막 8자를 입력하세요: <code style={{ background: "var(--surface-3)", padding: "1px 6px", borderRadius: 3, fontFamily: "var(--mono)" }}>{required}</code></>}>
        <input autoFocus className="input mono" value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={required} style={{ marginTop: 6 }} />
      </Field>
    </Dialog>
  );
}

// ===================== Audit Logs =====================
function AuditTab({ tenant }) {
  const events = window.MOCK.AUDIT_EVENTS;
  const [filter, setFilter] = useState3(new Set());
  const [showVerify, setShowVerify] = useState3(false);
  const [verifyResult, setVerifyResult] = useState3(null);
  const [open, setOpen] = useState3(null);

  const types = useMemo3(() => Array.from(new Set(events.map((e) => e.type))), [events]);
  const filtered = filter.size === 0 ? events : events.filter((e) => filter.has(e.type));

  return (
    <div className="stack-4">
      <ChainVerifyCard onOpen={() => setShowVerify(true)} result={verifyResult} />

      <div className="card">
        <div className="card__head" style={{ gap: 10, flexWrap: "wrap" }}>
          <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
            <Icons.Filter size={13} />
            {types.map((t) => (
              <button key={t}
                onClick={() => { const next = new Set(filter); next.has(t) ? next.delete(t) : next.add(t); setFilter(next); }}
                className={filter.has(t) ? "badge badge--accent" : "badge"}
                style={{ cursor: "pointer", border: filter.has(t) ? "1px solid var(--accent)" : "1px solid var(--border)" }}>
                {t}
              </button>
            ))}
            {filter.size > 0 && <button className="btn btn--ghost btn--xs" onClick={() => setFilter(new Set())}>전체 해제</button>}
          </div>
          <div className="row">
            <button className="btn btn--sm"><Icons.Calendar size={12} /> 최근 24시간 ▾</button>
            <button className="btn btn--sm"><Icons.Download size={12} /> 내보내기</button>
          </div>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>시각</th>
              <th>eventType</th>
              <th>actor</th>
              <th>subject</th>
              <th>payload</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((e, i) => (
              <tr key={i} onClick={() => setOpen(e)} style={{ cursor: "pointer" }}>
                <td>
                  <div className="stack-1">
                    <div style={{ fontWeight: 500, fontSize: 12 }}>{fmtDateTime(e.ts)}</div>
                    <div className="faint" style={{ fontSize: 11 }}>{timeAgo(e.ts)}</div>
                  </div>
                </td>
                <td><EventTypeBadge type={e.type} /></td>
                <td>
                  <div className="stack-1">
                    <span className="badge" style={{ fontSize: 10 }}>{e.actorType}</span>
                    <span className="mono faint" style={{ fontSize: 11 }}>{tail(e.actorId, 10)}</span>
                  </div>
                </td>
                <td>
                  <div className="stack-1">
                    <span className="badge" style={{ fontSize: 10 }}>{e.subjectType}</span>
                    <span className="mono faint" style={{ fontSize: 11 }}>{tail(e.subjectId, 12)}</span>
                  </div>
                </td>
                <td>
                  <code style={{ fontSize: 11, color: "var(--text-soft)", display: "inline-block", maxWidth: 360, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {JSON.stringify(e.payload)}
                  </code>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ChainVerifyDialog open={showVerify} onClose={() => setShowVerify(false)} onResult={(r) => { setVerifyResult(r); setShowVerify(false); }} />
      <PayloadDialog event={open} onClose={() => setOpen(null)} />
    </div>
  );
}

function EventTypeBadge({ type }) {
  const map = {
    CREDENTIAL_AUTHENTICATED: "success",
    CREDENTIAL_REGISTERED: "info",
    CREDENTIAL_REVOKED: "danger",
    API_KEY_ISSUED: "violet",
    API_KEY_REVOKED: "danger",
    WEBAUTHN_CONFIG_UPDATED: "warning",
    ATTESTATION_POLICY_UPDATED: "warning",
    SIGNATURE_COUNTER_REGRESSION: "danger",
    ATTESTATION_TRUST_FAILED: "danger",
  };
  return <span className={`badge badge--${map[type] || "default"} badge--dot mono`} style={{ fontSize: 10 }}>{type}</span>;
}

function ChainVerifyCard({ onOpen, result }) {
  return (
    <div className="card">
      <div className="card__body" style={{ display: "flex", gap: 14, alignItems: "center" }}>
        <div style={{ width: 40, height: 40, borderRadius: 10, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", flex: "none" }}>
          <Icons.Hash size={20} />
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 14, fontWeight: 600 }}>Audit Hash Chain 검증</div>
          <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
            기간 내 모든 audit row의 prevHash → SHA-256 chain을 재계산하여 변조 여부를 확인합니다.
          </div>
        </div>
        {result && (
          <div className="row" style={{ gap: 16 }}>
            <div className="stack-1" style={{ textAlign: "right" }}>
              <div className="muted" style={{ fontSize: 11 }}>verifiedRows</div>
              <div style={{ fontWeight: 600, fontFamily: "var(--mono)" }}>{fmt(result.verifiedRows)}</div>
            </div>
            {result.intact ? (
              <span className="badge badge--success badge--dot" style={{ fontSize: 12, padding: "4px 10px" }}>INTACT</span>
            ) : (
              <span className="badge badge--danger badge--dot" style={{ fontSize: 12, padding: "4px 10px" }}>위변조 {result.tampered.length}건</span>
            )}
          </div>
        )}
        <button className="btn btn--primary btn--sm" onClick={onOpen}><Icons.Hash size={12} /> 검증 실행</button>
        {result && result.intact && <button className="btn btn--sm"><Icons.Download size={12} /> 보고서</button>}
      </div>
    </div>
  );
}

function ChainVerifyDialog({ open, onClose, onResult }) {
  const [from, setFrom] = useState3("2026-05-01");
  const [to, setTo] = useState3("2026-05-15");
  const [running, setRunning] = useState3(false);
  const [progress, setProgress] = useState3(0);

  function run() {
    setRunning(true);
    setProgress(0);
    const tick = setInterval(() => {
      setProgress((p) => {
        const next = p + 7 + Math.random() * 9;
        if (next >= 100) {
          clearInterval(tick);
          setTimeout(() => {
            setRunning(false);
            onResult({ intact: true, verifiedRows: 184293, tampered: [], from, to });
          }, 200);
          return 100;
        }
        return next;
      });
    }, 90);
  }
  return (
    <Dialog open={open} onClose={onClose} wide title="Hash chain 검증"
      sub="대상 기간 안에 있는 audit row를 chain으로 재계산합니다. 행 수에 따라 수십 초가 소요될 수 있습니다."
      footer={<>
        <button className="btn" onClick={onClose} disabled={running}>취소</button>
        <button className="btn btn--primary" disabled={running} onClick={run}>{running ? `검증 중… ${Math.floor(progress)}%` : "검증 실행"}</button>
      </>}
    >
      <div className="grid-2" style={{ marginBottom: 14 }}>
        <Field label="from"><input className="input mono" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label="to"><input className="input mono" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>

      {running && (
        <div className="stack-3" style={{ padding: 14, border: "1px solid var(--border)", borderRadius: 8, background: "var(--surface-2)" }}>
          <div style={{ height: 6, borderRadius: 4, background: "var(--border)", overflow: "hidden" }}>
            <div style={{ width: `${progress}%`, height: "100%", background: "linear-gradient(90deg, var(--accent), var(--accent-hover))", transition: "width 90ms linear" }} />
          </div>
          <div className="row" style={{ justifyContent: "space-between", fontSize: 12, color: "var(--text-mute)", fontFamily: "var(--mono)" }}>
            <span>SHA-256 prevHash → currentHash …</span>
            <span>{fmt(Math.floor(progress * 1842))} / 184,293 행</span>
          </div>
        </div>
      )}

      {!running && (
        <div style={{ padding: 12, background: "var(--surface-3)", borderRadius: 8, fontSize: 12, color: "var(--text-soft)" }}>
          <div style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
            <Icons.Info size={13} />
            <div>
              검증은 read-only이며 audit row를 변경하지 않습니다. 결과는 화면에 인라인으로 표시되며, PDF로 내보낼 수 있습니다 (v1.1).
            </div>
          </div>
        </div>
      )}
    </Dialog>
  );
}

function PayloadDialog({ event, onClose }) {
  if (!event) return null;
  return (
    <Dialog open onClose={onClose} wide title="Audit Event"
      sub={fmtDateTime(event.ts)}
      footer={<button className="btn" onClick={onClose}>닫기</button>}
    >
      <div style={{ display: "grid", gridTemplateColumns: "130px 1fr", rowGap: 10, fontSize: 13, marginBottom: 14 }}>
        <div className="muted">eventType</div><div><EventTypeBadge type={event.type} /></div>
        <div className="muted">actor</div><div><span className="badge" style={{ marginRight: 6 }}>{event.actorType}</span><span className="mono" style={{ fontSize: 12 }}>{event.actorId}</span></div>
        <div className="muted">subject</div><div><span className="badge" style={{ marginRight: 6 }}>{event.subjectType}</span><span className="mono" style={{ fontSize: 12 }}>{event.subjectId}</span></div>
      </div>
      <div className="label">payload</div>
      <pre style={{ margin: 0, padding: 14, background: "var(--surface-3)", borderRadius: 8, fontSize: 12, fontFamily: "var(--mono)", overflow: "auto", color: "var(--text)" }}>
{JSON.stringify(event.payload, null, 2)}
      </pre>
    </Dialog>
  );
}

// ===================== Funnel =====================
function FunnelTab({ tenant }) {
  const f = window.MOCK.FUNNEL;
  const [window_, setWindow] = useState3(7);
  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">Conversion Funnel</h3>
            <div className="card__sub">ceremony 단계별 시도/성공 비율. 최근 {window_}일.</div>
          </div>
          <div className="row" style={{ gap: 4 }}>
            {[1, 7, 30].map((d) => (
              <button key={d} onClick={() => setWindow(d)} className="btn btn--sm" style={{ background: window_ === d ? "var(--accent-soft)" : "var(--surface)", color: window_ === d ? "var(--accent)" : "var(--text)", borderColor: window_ === d ? "var(--accent)" : "var(--border)" }}>{d === 1 ? "24h" : `${d}d`}</button>
            ))}
          </div>
        </div>
        <div className="card__body">
          <Funnel f={f} />
        </div>
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card__head"><h3 className="card__title">일별 인증 시도 vs 성공</h3></div>
          <div className="card__body">
            <BarChart data={f.series} />
          </div>
        </div>
        <div className="card">
          <div className="card__head"><h3 className="card__title">이벤트 타입별 분포</h3></div>
          <div className="card__body">
            <EventDistribution data={f.byEventType} />
          </div>
        </div>
      </div>
    </div>
  );
}

function Funnel({ f }) {
  const reg = f.registration; const auth = f.authentication;
  const steps = [
    { label: "등록 시도", value: reg.attempts, color: "var(--info)" },
    { label: "등록 성공", value: reg.success, color: "var(--accent)", ratio: reg.ratio },
    { label: "인증 시도", value: auth.attempts, color: "var(--violet)" },
    { label: "인증 성공", value: auth.success, color: "var(--success)", ratio: auth.ratio },
  ];
  const max = Math.max(...steps.map((s) => s.value));
  return (
    <div className="stack-3">
      {steps.map((s, i) => (
        <div key={s.label}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
            <div className="row" style={{ gap: 8 }}>
              <span style={{ width: 8, height: 8, borderRadius: 999, background: s.color }} />
              <span style={{ fontWeight: 500, fontSize: 13 }}>{s.label}</span>
              {s.ratio && <span className="badge badge--success" style={{ fontSize: 10 }}>{(s.ratio * 100).toFixed(1)}%</span>}
            </div>
            <div className="mono" style={{ fontSize: 13, fontWeight: 600 }}>{fmt(s.value)}</div>
          </div>
          <div style={{ height: 8, background: "var(--surface-3)", borderRadius: 999, overflow: "hidden" }}>
            <div style={{ height: "100%", width: `${(s.value / max) * 100}%`, background: s.color, borderRadius: 999, transition: "width 600ms ease" }} />
          </div>
        </div>
      ))}
    </div>
  );
}

function BarChart({ data }) {
  const max = Math.max(...data.map((d) => d.attempts));
  return (
    <div>
      <div style={{ display: "grid", gridTemplateColumns: `repeat(${data.length}, 1fr)`, alignItems: "flex-end", gap: 14, height: 160, padding: "0 4px" }}>
        {data.map((d) => (
          <div key={d.day} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6 }}>
            <div style={{ position: "relative", width: "100%", maxWidth: 32, height: 130, display: "flex", alignItems: "flex-end" }}>
              <div style={{ width: "100%", height: `${(d.attempts / max) * 100}%`, background: "var(--accent-soft-2)", borderRadius: "4px 4px 0 0", position: "relative" }}>
                <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, height: `${(d.success / d.attempts) * 100}%`, background: "var(--accent)", borderRadius: "4px 4px 0 0" }} />
              </div>
            </div>
            <div className="muted" style={{ fontSize: 11 }}>{d.day}</div>
          </div>
        ))}
      </div>
      <div className="row" style={{ marginTop: 10, gap: 14, fontSize: 11, color: "var(--text-mute)" }}>
        <div className="row" style={{ gap: 6 }}><span style={{ width: 10, height: 8, background: "var(--accent-soft-2)", borderRadius: 2 }} /> 시도</div>
        <div className="row" style={{ gap: 6 }}><span style={{ width: 10, height: 8, background: "var(--accent)", borderRadius: 2 }} /> 성공</div>
      </div>
    </div>
  );
}

function EventDistribution({ data }) {
  const total = data.reduce((a, b) => a + b.n, 0);
  const palette = ["var(--info)", "var(--accent)", "var(--violet)", "var(--success)", "var(--warning)", "var(--danger)"];
  return (
    <div className="stack-3">
      <div style={{ display: "flex", height: 12, borderRadius: 6, overflow: "hidden" }}>
        {data.map((d, i) => (
          <div key={d.type} style={{ flexBasis: `${(d.n / total) * 100}%`, background: palette[i % palette.length] }} title={`${d.type}: ${d.n}`} />
        ))}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6 }}>
        {data.map((d, i) => (
          <div key={d.type} className="row" style={{ justifyContent: "space-between", fontSize: 12 }}>
            <div className="row" style={{ gap: 6 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: palette[i % palette.length] }} />
              <span className="mono" style={{ fontSize: 11 }}>{d.type}</span>
            </div>
            <span className="mono muted">{fmt(d.n)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, { ApiKeysTab, CredentialsTab, AuditTab, FunnelTab });
