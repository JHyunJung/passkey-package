/* global React, ReactDOM, Icons, BrandMark, ToastHost, useToast, Sidebar, Header, LoginPage, TenantsListPage, TenantDetailPage, useTweaks, TweaksPanel, TweakSection, TweakRadio, TweakColor, TweakToggle */

const { useState, useMemo, useEffect } = React;

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "theme": "light",
  "density": "compact",
  "tableStyle": "lines",
  "sidebarMode": "labels",
  "accent": "#4f46e5"
}/*EDITMODE-END*/;

function App() {
  // Read initial state from URL hash for design-canvas embed.
  // Format: #me=platform|rp&tab=apikeys&theme=dark&density=compact&modal=plaintext
  const hashParams = useMemo(() => {
    const out = {};
    (location.hash || "").replace(/^#/, "").split("&").forEach((p) => {
      if (!p) return;
      const [k, v] = p.split("=");
      out[decodeURIComponent(k)] = decodeURIComponent(v || "");
    });
    return out;
  }, []);

  const initialMe = hashParams.me === "rp" ? window.MOCK.ADMIN_USERS.rp
    : hashParams.me === "platform" ? window.MOCK.ADMIN_USERS.platform
    : null;

  const initialRoute = (() => {
    if (!initialMe) return { name: "tenants" };
    if (initialMe.role === "RP_ADMIN") return { name: "tenant", tenantId: initialMe.tenantId, tab: hashParams.tab || "overview" };
    if (hashParams.tenant) return { name: "tenant", tenantId: hashParams.tenant, tab: hashParams.tab || "overview" };
    return { name: "tenants" };
  })();

  // Auth & route state
  const [me, setMe] = useState(initialMe);
  const [route, setRoute] = useState(initialRoute);

  const [t, setTweak] = useTweaks({
    ...TWEAK_DEFAULTS,
    ...(hashParams.theme ? { theme: hashParams.theme } : {}),
    ...(hashParams.density ? { density: hashParams.density } : {}),
    ...(hashParams.tableStyle ? { tableStyle: hashParams.tableStyle } : {}),
    ...(hashParams.sidebarMode ? { sidebarMode: hashParams.sidebarMode } : {}),
    ...(hashParams.accent ? { accent: hashParams.accent } : {}),
  });
  // Apply tweaks to document root
  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute("data-theme", t.theme);
    root.setAttribute("data-density", t.density);
    root.setAttribute("data-tablestyle", t.tableStyle);
    root.setAttribute("data-sidebar", t.sidebarMode);
    root.style.setProperty("--accent", t.accent);
    // Derive accent-hover and soft from accent.
    root.style.setProperty("--accent-hover", shade(t.accent, -10));
    root.style.setProperty("--accent-soft", withAlpha(t.accent, 0.12));
    root.style.setProperty("--accent-soft-2", withAlpha(t.accent, 0.22));
  }, [t]);

  function handleLogin(roleKey) {
    const user = window.MOCK.ADMIN_USERS[roleKey];
    setMe(user);
    // role-based auto routing
    if (user.role === "RP_ADMIN") {
      setRoute({ name: "tenant", tenantId: user.tenantId, tab: "overview" });
    } else {
      setRoute({ name: "tenants" });
    }
  }
  function handleLogout() { setMe(null); setRoute({ name: "tenants" }); }
  function switchRole() {
    const next = me.role === "PLATFORM_OPERATOR" ? window.MOCK.ADMIN_USERS.rp : window.MOCK.ADMIN_USERS.platform;
    setMe(next);
    if (next.role === "RP_ADMIN") setRoute({ name: "tenant", tenantId: next.tenantId, tab: "overview" });
    else setRoute({ name: "tenants" });
  }

  // Command palette state (hooks must run before any early return)
  const [paletteOpen, setPaletteOpen] = useState(false);
  useEffect(() => {
    function k(e) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    }
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, []);

  if (!me) {
    return (
      <ToastHost>
        <LoginPage onLogin={handleLogin} />
        {hashParams.chrome !== "hide" && <TweaksPanelBound t={t} setTweak={setTweak} />}
      </ToastHost>
    );
  }

  const tenant = route.name === "tenant" ? window.MOCK.TENANTS.find((x) => x.id === route.tenantId) : null;
  const breadcrumb = buildBreadcrumb(route, tenant, me, setRoute);

  function paletteAction(kind) {
    if (kind === "logout") handleLogout();
    if (kind === "switch-role") switchRole();
    if (kind === "new-tenant") setRoute({ name: "tenants" });
    if (kind === "verify-chain" && tenant) setRoute({ name: "tenant", tenantId: tenant.id, tab: "audit" });
  }

  return (
    <ToastHost>
      <div className="app" style={{ gridTemplateAreas: '"sidebar content"' }}>
        <Sidebar me={me} currentRoute={route} onNavigate={setRoute} tenant={tenant} sidebarMode={t.sidebarMode} />
        <div className="content" style={{ gridArea: "content" }}>
          <Header me={me} onLogout={handleLogout} onSwitchRole={switchRole} breadcrumb={breadcrumb} onOpenPalette={() => setPaletteOpen(true)} />
          <Main route={route} setRoute={setRoute} me={me} />
        </div>
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} me={me} onNavigate={setRoute} onAction={paletteAction} />
      <IdleSessionModal onExtend={() => { /* refresh /me */ }} onLogout={handleLogout} />
      {hashParams.chrome !== "hide" && <TweaksPanelBound t={t} setTweak={setTweak} />}
    </ToastHost>
  );
}

function Main({ route, setRoute, me }) {
  if (route.name === "tenants") {
    return <TenantsListPage
      tenants={window.MOCK.TENANTS}
      onOpen={(id) => setRoute({ name: "tenant", tenantId: id, tab: "overview" })}
      onCreate={() => {}}
    />;
  }
  if (route.name === "activity") {
    return <ActivityPage onOpenTenant={(id) => setRoute({ name: "tenant", tenantId: id, tab: "overview" })} />;
  }
  if (route.name === "audit-chain") {
    return <AuditChainPage onOpenTenant={(id) => setRoute({ name: "tenant", tenantId: id, tab: "audit" })} />;
  }
  if (route.name === "settings") {
    return <SettingsPage />;
  }
  if (route.name === "tenant") {
    const tenant = window.MOCK.TENANTS.find((x) => x.id === route.tenantId);
    if (!tenant) return <div className="page"><div className="card"><div className="card__body">Tenant를 찾을 수 없습니다.</div></div></div>;
    // RP_ADMIN trying to access foreign tenant
    if (me.role === "RP_ADMIN" && me.tenantId !== tenant.id) {
      return <div className="page"><div className="card"><div className="card__body" style={{ padding: 40, textAlign: "center" }}>
        <Icons.Lock size={28} />
        <div style={{ fontWeight: 600, marginTop: 10 }}>이 tenant에 대한 권한이 없습니다.</div>
        <div className="muted" style={{ marginTop: 6, fontSize: 13 }}>RP_ADMIN은 자기 tenant만 접근 가능합니다.</div>
        <button className="btn btn--primary" style={{ marginTop: 16 }} onClick={() => setRoute({ name: "tenant", tenantId: me.tenantId, tab: "overview" })}>내 tenant로 돌아가기</button>
      </div></div></div>;
    }
    return <TenantDetailPage tenant={tenant} currentTab={route.tab || "overview"} onTabChange={(t) => setRoute({ ...route, tab: t })} me={me} />;
  }
  return null;
}

function buildBreadcrumb(route, tenant, me, setRoute) {
  const items = [];
  if (route.name === "tenants" || (route.name === "tenant" && me.role === "PLATFORM_OPERATOR")) {
    items.push({ label: "Tenants", onClick: route.name === "tenants" ? undefined : () => setRoute({ name: "tenants" }) });
  }
  if (route.name === "tenant" && tenant) {
    items.push({ label: tenant.name, onClick: () => setRoute({ name: "tenant", tenantId: tenant.id, tab: "overview" }) });
    const tabName = { overview: "개요", webauthn: "WebAuthn", aaguid: "AAGUID 정책", apikeys: "API Keys", credentials: "Credentials", audit: "Audit Logs", funnel: "Funnel" }[route.tab || "overview"];
    items.push({ label: tabName });
  } else if (route.name === "activity") {
    items.push({ label: "Activity" });
  } else if (route.name === "audit-chain") {
    items.push({ label: "Audit Chain Monitor" });
  } else if (route.name === "settings") {
    items.push({ label: "설정" });
  }
  return items;
}

// ============ Tweaks panel ============
function TweaksPanelBound({ t, setTweak }) {
  return (
    <TweaksPanel title="Tweaks">
      <TweakSection label="테마" />
      <TweakRadio label="모드" value={t.theme} onChange={(v) => setTweak("theme", v)} options={[{value: "light", label: "Light"}, {value: "dark", label: "Dark"}]} />
      <TweakColor label="Accent" value={t.accent} onChange={(v) => setTweak("accent", v)} options={["#4f46e5", "#5b5bd6", "#7c3aed", "#0f766e", "#db7706"]} />

      <TweakSection label="레이아웃" />
      <TweakRadio label="밀도" value={t.density} onChange={(v) => setTweak("density", v)} options={[{value: "compact", label: "Compact"}, {value: "comfortable", label: "Comfort"}]} />
      <TweakRadio label="테이블" value={t.tableStyle} onChange={(v) => setTweak("tableStyle", v)} options={[{value: "lines", label: "구분선"}, {value: "striped", label: "줄무늬"}, {value: "borderless", label: "보더리스"}]} />
      <TweakRadio label="사이드바" value={t.sidebarMode} onChange={(v) => setTweak("sidebarMode", v)} options={[{value: "labels", label: "라벨"}, {value: "icons", label: "아이콘만"}]} />
    </TweaksPanel>
  );
}

// utilities
function shade(hex, percent) {
  // simple lighten/darken
  const h = hex.replace("#", "");
  const num = parseInt(h, 16);
  let r = (num >> 16) + Math.round(2.55 * percent);
  let g = ((num >> 8) & 0xff) + Math.round(2.55 * percent);
  let b = (num & 0xff) + Math.round(2.55 * percent);
  r = Math.max(0, Math.min(255, r));
  g = Math.max(0, Math.min(255, g));
  b = Math.max(0, Math.min(255, b));
  return `rgb(${r},${g},${b})`;
}
function withAlpha(hex, a) {
  const h = hex.replace("#", "");
  const num = parseInt(h, 16);
  const r = num >> 16, g = (num >> 8) & 0xff, b = num & 0xff;
  return `rgba(${r},${g},${b},${a})`;
}

const root = ReactDOM.createRoot(document.getElementById("root"));
root.render(<App />);
