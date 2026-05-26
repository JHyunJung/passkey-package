/* global React, Icons, Dialog */
const { useState: useState7, useEffect: useEffect7, useRef: useRef7, useMemo: useMemo7 } = React;

// ===== Idle session timeout modal =====
function IdleSessionModal({ onExtend, onLogout }) {
  // Demo: shows after 90s of inactivity in this prototype.
  // In prod, it would be tied to actual session expiry.
  const [open, setOpen] = useState7(false);
  const [secondsLeft, setSecondsLeft] = useState7(60);
  const idleTimer = useRef7(null);

  useEffect7(() => {
    function bumpIdle() {
      clearTimeout(idleTimer.current);
      idleTimer.current = setTimeout(() => {
        setSecondsLeft(60);
        setOpen(true);
      }, 90 * 1000); // 90s for demo
    }
    bumpIdle();
    const handler = () => { if (!open) bumpIdle(); };
    ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.addEventListener(e, handler, { passive: true }));
    return () => {
      clearTimeout(idleTimer.current);
      ["mousemove", "keydown", "click", "scroll"].forEach((e) => window.removeEventListener(e, handler));
    };
  }, [open]);

  useEffect7(() => {
    if (!open) return;
    const tick = setInterval(() => {
      setSecondsLeft((s) => {
        if (s <= 1) { clearInterval(tick); setOpen(false); onLogout?.(); return 0; }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(tick);
  }, [open, onLogout]);

  if (!open) return null;
  return (
    <Dialog open={open} onClose={() => {}} closeOnScrim={false}
      title={<span style={{ display: "flex", alignItems: "center", gap: 8 }}><Icons.Lock size={18} /> 세션이 곧 만료됩니다</span>}
      sub={`${secondsLeft}초 후 자동으로 로그아웃됩니다.`}
      footer={<>
        <button className="btn" onClick={() => { setOpen(false); onLogout(); }}>지금 로그아웃</button>
        <button className="btn btn--primary" onClick={() => { setOpen(false); onExtend(); }}>세션 연장</button>
      </>}
    >
      <div className="stack-3">
        <p style={{ margin: 0, fontSize: 13, color: "var(--text-soft)", lineHeight: 1.6 }}>
          보안을 위해 30분 동안 활동이 없으면 자동으로 로그아웃됩니다. 작업을 계속하려면 <strong>세션 연장</strong>을 눌러주세요.
        </p>
        <div style={{ height: 6, borderRadius: 4, background: "var(--surface-3)", overflow: "hidden" }}>
          <div style={{ width: `${(secondsLeft / 60) * 100}%`, height: "100%", background: secondsLeft > 20 ? "var(--accent)" : "var(--danger)", transition: "width 1s linear, background 220ms" }} />
        </div>
        <div className="muted" style={{ fontSize: 12 }}>
          모든 mutation은 audit log에 기록되어 있으므로 작업 내역은 보존됩니다.
        </div>
      </div>
    </Dialog>
  );
}

// ===== Command palette (⌘K) =====
function CommandPalette({ open, onClose, me, onNavigate, onAction }) {
  const [q, setQ] = useState7("");
  const inputRef = useRef7(null);

  useEffect7(() => {
    if (open) { setQ(""); setTimeout(() => inputRef.current?.focus(), 50); }
  }, [open]);

  const isPlatform = me?.role === "PLATFORM_OPERATOR";
  const allCommands = useMemo7(() => {
    const items = [];

    // Pages — context-dependent
    if (isPlatform) {
      items.push({ group: "이동", icon: "Building", label: "Tenants 목록", hint: "전체 RP 회사", action: () => onNavigate({ name: "tenants" }) });
      items.push({ group: "이동", icon: "Activity", label: "Activity", hint: "cross-tenant 활동 피드", action: () => onNavigate({ name: "activity" }) });
      items.push({ group: "이동", icon: "Hash", label: "Audit Chain Monitor", hint: "전 tenant 무결성 보드", action: () => onNavigate({ name: "audit-chain" }) });
      items.push({ group: "이동", icon: "Cog", label: "설정", hint: "Admin · MDS · 시스템", action: () => onNavigate({ name: "settings" }) });
    }

    // Tenants — only platform sees all
    if (isPlatform) {
      window.MOCK.TENANTS.forEach((t) => {
        items.push({ group: "Tenant", icon: "Building", label: t.name, hint: `${t.slug} · ${t.id.slice(-8)}`, action: () => onNavigate({ name: "tenant", tenantId: t.id, tab: "overview" }) });
      });
    } else if (me?.tenantId) {
      // RP_ADMIN sees only their tenant tabs
      const tabs = [
        { id: "overview", label: "개요", icon: "Activity" },
        { id: "webauthn", label: "WebAuthn", icon: "Globe" },
        { id: "aaguid", label: "AAGUID 정책", icon: "Shield" },
        { id: "apikeys", label: "API Keys", icon: "Key" },
        { id: "credentials", label: "Credentials", icon: "Fingerprint" },
        { id: "audit", label: "Audit Logs", icon: "Receipt" },
        { id: "funnel", label: "Funnel", icon: "Activity" },
      ];
      tabs.forEach((tb) => items.push({ group: "현재 Tenant", icon: tb.icon, label: tb.label, hint: `내 tenant > ${tb.label}`, action: () => onNavigate({ name: "tenant", tenantId: me.tenantId, tab: tb.id }) }));
    }

    // Quick actions
    items.push({ group: "액션", icon: "Plus", label: "신규 tenant 생성", hint: "PLATFORM_OPERATOR만", action: () => onAction("new-tenant"), disabled: !isPlatform });
    items.push({ group: "액션", icon: "Hash", label: "Hash chain 즉시 검증", hint: "현재 tenant", action: () => onAction("verify-chain") });
    items.push({ group: "액션", icon: "Refresh", label: "Role 전환 (데모)", hint: "PLATFORM ↔ RP_ADMIN", action: () => onAction("switch-role") });
    items.push({ group: "액션", icon: "LogOut", label: "로그아웃", action: () => onAction("logout") });

    return items;
  }, [isPlatform, me, onNavigate, onAction]);

  const filtered = useMemo7(() => {
    if (!q) return allCommands;
    const qq = q.toLowerCase();
    return allCommands.filter((c) => c.label.toLowerCase().includes(qq) || (c.hint || "").toLowerCase().includes(qq) || c.group.toLowerCase().includes(qq));
  }, [q, allCommands]);

  const [selIdx, setSelIdx] = useState7(0);
  useEffect7(() => { setSelIdx(0); }, [q]);
  useEffect7(() => {
    if (!open) return;
    function k(e) {
      if (e.key === "Escape") { onClose(); return; }
      if (e.key === "ArrowDown") { e.preventDefault(); setSelIdx((i) => Math.min(filtered.length - 1, i + 1)); }
      if (e.key === "ArrowUp") { e.preventDefault(); setSelIdx((i) => Math.max(0, i - 1)); }
      if (e.key === "Enter") {
        e.preventDefault();
        const it = filtered[selIdx];
        if (it && !it.disabled) { it.action(); onClose(); }
      }
    }
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [open, filtered, selIdx, onClose]);

  if (!open) return null;

  // Group items
  const groups = {};
  filtered.forEach((c, i) => {
    groups[c.group] = groups[c.group] || [];
    groups[c.group].push({ ...c, _i: i });
  });

  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{
        background: "var(--surface)", border: "1px solid var(--border)",
        borderRadius: 12, width: "min(640px, 92vw)", maxHeight: "70vh",
        boxShadow: "var(--shadow-lg)", overflow: "hidden",
        display: "flex", flexDirection: "column",
        marginTop: "10vh",
      }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "14px 16px", borderBottom: "1px solid var(--border)" }}>
          <Icons.Search size={18} />
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="tenant, credential, audit ID 검색…"
            style={{ flex: 1, border: 0, outline: "none", background: "transparent", fontSize: 15, color: "var(--text)" }}
          />
          <span className="kbd">esc</span>
        </div>
        <div style={{ flex: 1, overflow: "auto", padding: 6 }}>
          {Object.keys(groups).length === 0 && (
            <div style={{ padding: 36, textAlign: "center", color: "var(--text-mute)", fontSize: 13 }}>
              일치하는 결과가 없습니다.
            </div>
          )}
          {Object.entries(groups).map(([g, items]) => (
            <div key={g}>
              <div style={{ padding: "8px 12px 4px", fontSize: 10, fontWeight: 600, color: "var(--text-mute)", textTransform: "uppercase", letterSpacing: "0.06em" }}>{g}</div>
              {items.map((it) => {
                const Active = it._i === selIdx;
                const Ic = Icons[it.icon] || Icons.ChevronRight;
                return (
                  <button
                    key={it._i}
                    disabled={it.disabled}
                    onClick={() => { it.action(); onClose(); }}
                    onMouseEnter={() => setSelIdx(it._i)}
                    style={{
                      display: "flex", alignItems: "center", gap: 10, width: "100%",
                      padding: "8px 12px", border: 0, borderRadius: 6,
                      background: Active ? "var(--accent-soft)" : "transparent",
                      color: it.disabled ? "var(--text-faint)" : (Active ? "var(--accent)" : "var(--text)"),
                      cursor: it.disabled ? "not-allowed" : "pointer",
                      textAlign: "left", fontSize: 13,
                    }}
                  >
                    <Ic size={14} />
                    <span style={{ flex: 1 }}>{it.label}</span>
                    {it.hint && <span className="muted" style={{ fontSize: 11 }}>{it.hint}</span>}
                    {Active && !it.disabled && <Icons.ChevronRight size={12} />}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <div style={{ display: "flex", justifyContent: "space-between", padding: "10px 14px", borderTop: "1px solid var(--border)", fontSize: 11, color: "var(--text-mute)" }}>
          <div className="row" style={{ gap: 12 }}>
            <span><span className="kbd">↑</span> <span className="kbd">↓</span> 이동</span>
            <span><span className="kbd">↵</span> 실행</span>
          </div>
          <span>{filtered.length} 결과</span>
        </div>
      </div>
    </div>
  );
}

window.IdleSessionModal = IdleSessionModal;
window.CommandPalette = CommandPalette;
