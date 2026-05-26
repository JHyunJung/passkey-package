/* global React, Icons, BrandMark */
// Shared UI components: Dialog, Toast, Sidebar, Header, EmptyState, etc.
const { useState, useEffect, useRef, createContext, useContext, useCallback } = React;

// ===== Toast =====
const ToastCtx = createContext(null);
function ToastHost({ children }) {
  const [toasts, setToasts] = useState([]);
  const push = useCallback((t) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((arr) => [...arr, { id, ...t }]);
    setTimeout(() => setToasts((arr) => arr.filter((x) => x.id !== id)), t.duration || 4200);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-rack">
        {toasts.map((t) => (
          <div key={t.id} className="toast" role="status">
            <div className={`toast__icon toast__icon--${t.kind || "ok"}`}>
              {t.kind === "err" ? <Icons.X size={11} /> : t.kind === "warn" ? <Icons.Alert size={11} /> : <Icons.Check size={11} />}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="toast__title">{t.title}</div>
              {t.message && <div className="toast__sub">{t.message}</div>}
              {t.traceId && <div className="toast__trace">traceId · {t.traceId}</div>}
            </div>
            <button className="btn btn--ghost btn--xs" onClick={() => setToasts((a) => a.filter((x) => x.id !== t.id))} aria-label="닫기"><Icons.X size={12} /></button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}
function useToast() { return useContext(ToastCtx); }

// ===== Dialog =====
function Dialog({ open, onClose, title, sub, children, footer, wide, closeOnScrim = true }) {
  useEffect(() => {
    if (!open) return;
    const k = (e) => { if (e.key === "Escape") onClose?.(); };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && closeOnScrim) onClose?.(); }}>
      <div className={`dialog${wide ? " dialog--wide" : ""}`} role="dialog" aria-modal="true">
        <div className="dialog__head">
          <h3 className="dialog__title">{title}</h3>
          {sub && <div className="dialog__sub">{sub}</div>}
        </div>
        <div className="dialog__body">{children}</div>
        {footer && <div className="dialog__foot">{footer}</div>}
      </div>
    </div>
  );
}

// ===== Sidebar =====
const NAV_PLATFORM = [
  { id: "tenants", label: "Tenants", icon: "Building" },
  { id: "activity", label: "Activity", icon: "Activity" },
  { id: "audit-chain", label: "Audit Chain", icon: "Hash" },
  { id: "settings", label: "설정", icon: "Cog" },
];

const NAV_RP = [
  { id: "overview", label: "개요", icon: "Activity" },
  { id: "webauthn", label: "WebAuthn", icon: "Globe" },
  { id: "aaguid", label: "AAGUID 정책", icon: "Shield" },
  { id: "apikeys", label: "API Keys", icon: "Key" },
  { id: "credentials", label: "Credentials", icon: "Fingerprint" },
  { id: "audit", label: "Audit Logs", icon: "Receipt" },
  { id: "funnel", label: "Funnel", icon: "Activity" },
];

function Sidebar({ me, currentRoute, onNavigate, tenant, sidebarMode = "labels" }) {
  const isPlatform = me.role === "PLATFORM_OPERATOR";
  // Build a contextual nav: when inside a tenant, show tenant-tab nav under the tenant name.
  return (
    <aside style={{
      gridArea: "sidebar",
      background: "var(--surface)",
      borderRight: "1px solid var(--border)",
      display: "flex",
      flexDirection: "column",
      position: "sticky",
      top: 0,
      height: "100vh",
      overflow: "hidden",
    }}>
      <div style={{ padding: sidebarMode === "icons" ? "16px 8px" : "16px 18px", borderBottom: "1px solid var(--border)", display: "flex", alignItems: "center", gap: 10 }}>
        <BrandMark size={26} />
        {sidebarMode === "labels" && (
          <div className="stack-1" style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 13, lineHeight: 1.2, letterSpacing: "-0.01em" }}>Passkey Admin</div>
            <div style={{ fontSize: 11, color: "var(--text-mute)", lineHeight: 1.2 }}>Crosscert · prod</div>
          </div>
        )}
      </div>

      {/* Tenant context block */}
      {tenant && sidebarMode === "labels" && (
        <div style={{ padding: "12px 14px", borderBottom: "1px solid var(--border)" }}>
          {isPlatform && (
            <button className="btn btn--ghost btn--xs" onClick={() => onNavigate({ name: "tenants" })} style={{ marginBottom: 6, padding: "2px 4px", color: "var(--text-mute)" }}>
              <Icons.ChevronLeft size={12} /> Tenants
            </button>
          )}
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div style={{ width: 24, height: 24, borderRadius: 6, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 11 }}>
              {tenant.name.slice(0, 1)}
            </div>
            <div className="stack-1" style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600, fontSize: 13, letterSpacing: "-0.01em", lineHeight: 1.1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{tenant.name}</div>
              <div className="mono" style={{ fontSize: 11, color: "var(--text-mute)", lineHeight: 1.1 }}>{tenant.slug}</div>
            </div>
          </div>
        </div>
      )}

      <nav style={{ padding: "10px 8px", flex: 1, overflow: "auto" }}>
        {tenant ? (
          NAV_RP.map((item) => <NavBtn key={item.id} item={item} active={currentRoute.tab === item.id} mode={sidebarMode} onClick={() => onNavigate({ name: "tenant", tenantId: tenant.id, tab: item.id })} />)
        ) : (
          NAV_PLATFORM.map((item) => <NavBtn key={item.id} item={item} active={currentRoute.name === item.id} mode={sidebarMode} onClick={() => onNavigate({ name: item.id })} />)
        )}
      </nav>

      {/* Footer: audit chain status (v1.1 sneak peek) */}
      {sidebarMode === "labels" && (
        <div style={{ padding: "10px 14px", borderTop: "1px solid var(--border)" }}>
          <div className="stack-1" style={{ padding: "8px 10px", background: "var(--success-soft)", borderRadius: 8, border: "1px solid color-mix(in oklab, var(--success) 20%, transparent)" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 11, fontWeight: 600, color: "var(--success)" }}>
              <span style={{ width: 6, height: 6, borderRadius: 999, background: "var(--success)", boxShadow: "0 0 0 3px color-mix(in oklab, var(--success) 25%, transparent)" }}></span>
              AUDIT CHAIN OK
            </div>
            <div style={{ fontSize: 11, color: "var(--text-mute)" }}>마지막 검증 · 2분 전 · 1,284,920 행</div>
          </div>
        </div>
      )}
    </aside>
  );
}

function NavBtn({ item, active, onClick, mode }) {
  const IconC = Icons[item.icon] || Icons.Cog;
  return (
    <button
      onClick={onClick}
      title={mode === "icons" ? item.label : undefined}
      style={{
        display: "flex", alignItems: "center", gap: 10,
        width: "100%", padding: mode === "icons" ? "9px 0" : "8px 10px",
        justifyContent: mode === "icons" ? "center" : "flex-start",
        background: active ? "var(--accent-soft)" : "transparent",
        color: active ? "var(--accent)" : "var(--text-soft)",
        border: "0", borderRadius: 7,
        fontSize: 13, fontWeight: active ? 600 : 500, cursor: "pointer",
        textAlign: "left", marginBottom: 1,
      }}
      onMouseEnter={(e) => { if (!active) e.currentTarget.style.background = "var(--surface-3)"; }}
      onMouseLeave={(e) => { if (!active) e.currentTarget.style.background = "transparent"; }}
    >
      <IconC size={16} />
      {mode === "labels" && <span>{item.label}</span>}
    </button>
  );
}

// ===== Header =====
function Header({ me, onLogout, onSwitchRole, breadcrumb, onOpenPalette }) {
  const [menuOpen, setMenuOpen] = useState(false);
  return (
    <header style={{
      height: 52, borderBottom: "1px solid var(--border)",
      background: "var(--surface)", padding: "0 24px",
      display: "flex", alignItems: "center", gap: 16,
      position: "sticky", top: 0, zIndex: 30,
    }}>
      <Breadcrumb items={breadcrumb} />
      <div className="spacer" />

      {/* Global search — opens command palette */}
      <button onClick={onOpenPalette} style={{
        position: "relative", width: 280, textAlign: "left",
        display: "flex", alignItems: "center", gap: 8,
        padding: "0 8px 0 30px", height: 32,
        border: "1px solid var(--border)", borderRadius: 6,
        background: "var(--surface)", color: "var(--text-mute)", cursor: "pointer",
        fontSize: 12, fontFamily: "inherit",
      }}>
        <span style={{ position: "absolute", left: 9, top: "50%", transform: "translateY(-50%)" }}>
          <Icons.Search size={14} />
        </span>
        <span style={{ flex: 1 }}>tenant, credential, audit ID 검색…</span>
        <span className="kbd">⌘K</span>
      </button>

      {/* User menu */}
      <div style={{ position: "relative" }}>
        <button className="btn btn--ghost" onClick={() => setMenuOpen((v) => !v)} style={{ padding: "4px 8px", gap: 8 }}>
          <div style={{ width: 26, height: 26, borderRadius: 999, background: "var(--accent)", color: "white", display: "grid", placeItems: "center", fontWeight: 700, fontSize: 12 }}>
            {me.displayName.slice(0, 1)}
          </div>
          <div className="stack-1" style={{ alignItems: "flex-start", lineHeight: 1.1 }}>
            <div style={{ fontSize: 12, fontWeight: 600 }}>{me.displayName}</div>
            <div style={{ fontSize: 10, color: "var(--text-mute)" }}>{me.email}</div>
          </div>
          <span className={`badge ${me.role === "PLATFORM_OPERATOR" ? "badge--violet" : "badge--info"}`}>
            {me.role === "PLATFORM_OPERATOR" ? "PLATFORM" : "RP_ADMIN"}
          </span>
          <Icons.ChevronDown size={14} />
        </button>
        {menuOpen && (
          <>
            <div onMouseDown={() => setMenuOpen(false)} style={{ position: "fixed", inset: 0, zIndex: 30 }} />
            <div style={{
              position: "absolute", top: "100%", right: 0, marginTop: 6,
              background: "var(--surface)", border: "1px solid var(--border)",
              borderRadius: 10, boxShadow: "var(--shadow-lg)",
              minWidth: 240, zIndex: 31, overflow: "hidden",
            }}>
              <div style={{ padding: "12px 14px", borderBottom: "1px solid var(--border)" }}>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{me.displayName}</div>
                <div style={{ fontSize: 12, color: "var(--text-mute)", marginTop: 2 }}>{me.email}</div>
                <div className="row" style={{ marginTop: 8, gap: 6 }}>
                  <span className={`badge ${me.role === "PLATFORM_OPERATOR" ? "badge--violet" : "badge--info"}`}>{me.role}</span>
                  {me.tenantId && <span className="badge mono" style={{ fontSize: 10 }}>{me.tenantId.slice(-8)}</span>}
                </div>
              </div>
              <div style={{ padding: 6 }}>
                <MenuItem icon="Refresh" label="role 전환 (데모)" onClick={() => { setMenuOpen(false); onSwitchRole(); }} />
                <MenuItem icon="ExternalLink" label="API 문서 열기" />
                <MenuItem icon="LogOut" label="로그아웃" onClick={() => { setMenuOpen(false); onLogout(); }} />
              </div>
            </div>
          </>
        )}
      </div>
    </header>
  );
}
function MenuItem({ icon, label, onClick }) {
  const I = Icons[icon] || Icons.ChevronRight;
  return (
    <button onClick={onClick} style={{ display: "flex", alignItems: "center", gap: 10, width: "100%", padding: "8px 10px", border: 0, background: "transparent", borderRadius: 6, fontSize: 13, color: "var(--text)", cursor: "pointer" }}
      onMouseEnter={(e) => e.currentTarget.style.background = "var(--surface-3)"}
      onMouseLeave={(e) => e.currentTarget.style.background = "transparent"}
    >
      <I size={14} />
      <span>{label}</span>
    </button>
  );
}

// ===== Breadcrumb =====
function Breadcrumb({ items }) {
  if (!items || items.length === 0) return null;
  return (
    <nav style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 13, color: "var(--text-soft)", minWidth: 0 }}>
      {items.map((it, i) => (
        <React.Fragment key={i}>
          {i > 0 && <Icons.ChevronRight size={13} />}
          {it.onClick ? (
            <button onClick={it.onClick} className="btn btn--ghost btn--xs" style={{ padding: "2px 6px", color: i === items.length - 1 ? "var(--text)" : "var(--text-mute)", fontWeight: i === items.length - 1 ? 600 : 500 }}>{it.label}</button>
          ) : (
            <span style={{ color: i === items.length - 1 ? "var(--text)" : "var(--text-mute)", fontWeight: i === items.length - 1 ? 600 : 500 }}>{it.label}</span>
          )}
        </React.Fragment>
      ))}
    </nav>
  );
}

// ===== Empty State =====
function EmptyState({ icon = "Sparkles", title, description, action }) {
  const I = Icons[icon] || Icons.Sparkles;
  return (
    <div style={{ padding: "48px 24px", textAlign: "center" }}>
      <div style={{ width: 44, height: 44, borderRadius: 12, background: "var(--accent-soft)", color: "var(--accent)", display: "grid", placeItems: "center", margin: "0 auto 12px" }}>
        <I size={22} />
      </div>
      <div style={{ fontSize: 14, fontWeight: 600, color: "var(--text)" }}>{title}</div>
      {description && <div style={{ fontSize: 13, color: "var(--text-mute)", marginTop: 6, maxWidth: 380, marginLeft: "auto", marginRight: "auto" }}>{description}</div>}
      {action && <div style={{ marginTop: 14 }}>{action}</div>}
    </div>
  );
}

// ===== Status badge =====
function StatusBadge({ status }) {
  const map = {
    ACTIVE: "success",
    REVOKED: "danger",
    SUSPENDED: "warning",
    PENDING: "info",
  };
  return <span className={`badge badge--${map[status] || "default"} badge--dot`}>{status}</span>;
}

// ===== Misc =====
function CopyBtn({ value, label = "복사" }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="btn btn--sm"
      onClick={(e) => { e.stopPropagation(); navigator.clipboard?.writeText(value); setCopied(true); setTimeout(() => setCopied(false), 1500); }}
    >
      {copied ? <Icons.Check size={13} /> : <Icons.Copy size={13} />}
      {copied ? "복사됨" : label}
    </button>
  );
}

function timeAgo(iso) {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "—";
  const diff = Math.max(0, (Date.now() - t) / 1000);
  if (diff < 60) return diff < 2 ? "방금" : `${Math.floor(diff)}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)}일 전`;
  return new Date(iso).toLocaleDateString("ko-KR");
}
function fmtDateTime(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString("ko-KR", { year: "2-digit", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}
function tail(s, n = 8) {
  if (!s) return "—";
  return s.length <= n ? s : `…${s.slice(-n)}`;
}
function fmt(n) {
  return new Intl.NumberFormat("ko-KR").format(n);
}

Object.assign(window, {
  ToastHost, useToast, Dialog,
  Sidebar, Header, Breadcrumb,
  EmptyState, StatusBadge, CopyBtn,
  timeAgo, fmtDateTime, tail, fmt,
});
