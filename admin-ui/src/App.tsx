import { useState, useEffect, useMemo, useCallback } from 'react';
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';
import TenantsListPage from '@/pages/TenantsListPage';
import TenantDetailRoute from '@/pages/TenantDetailPage';
import { ToastHost } from '@/shell/ToastHost';
import { Sidebar } from '@/shell/Sidebar';
import { Header } from '@/shell/Header';
import { IdleSessionModal } from '@/extras/IdleSessionModal';
import { CommandPalette } from '@/extras/CommandPalette';
import { TweaksPanel, TweakSection, TweakRadio, TweakColor } from '@/tweaks/TweaksPanel';
import { useTweaks } from '@/tweaks/useTweaks';
import type { Tweaks } from '@/tweaks/useTweaks';
import LoginPage from '@/pages/LoginPage';
import ActivityPage from '@/pages/ActivityPage';
import AuditChainPage from '@/pages/AuditChainPage';
import SettingsPage from '@/pages/SettingsPage';
import { api } from '@/api/client';
import type { Me } from '@/api/types';

// ── Route type (mirrors design app.jsx shape) ────────────────────────────────

type AppRoute =
  | { name: 'tenants' }
  | { name: 'tenant'; tenantId: string; tab: string }
  | { name: 'activity' }
  | { name: 'audit-chain' }
  | { name: 'settings' };

function urlToRoute(pathname: string, search: URLSearchParams): AppRoute {
  if (pathname.startsWith('/tenants/')) {
    const id = pathname.split('/')[2];
    return { name: 'tenant', tenantId: id, tab: search.get('tab') || 'overview' };
  }
  if (pathname === '/activity') return { name: 'activity' };
  if (pathname === '/audit-chain') return { name: 'audit-chain' };
  if (pathname === '/settings') return { name: 'settings' };
  return { name: 'tenants' };
}

function routeToUrl(r: AppRoute): string {
  if (r.name === 'tenants') return '/tenants';
  if (r.name === 'tenant') return `/tenants/${r.tenantId}?tab=${r.tab}`;
  if (r.name === 'activity') return '/activity';
  if (r.name === 'audit-chain') return '/audit-chain';
  return '/settings';
}

// ── Breadcrumb builder ───────────────────────────────────────────────────────

function buildBreadcrumb(
  route: AppRoute,
  tenant: { id: string; name: string } | null,
  me: Me,
  navigate: (r: AppRoute) => void,
) {
  const items: { label: string; onClick?: () => void }[] = [];

  if (route.name === 'tenants' || (route.name === 'tenant' && me.role === 'PLATFORM_OPERATOR')) {
    items.push({
      label: 'Tenants',
      onClick: route.name === 'tenants' ? undefined : () => navigate({ name: 'tenants' }),
    });
  }

  if (route.name === 'tenant' && tenant) {
    items.push({
      label: tenant.name,
      onClick: () => navigate({ name: 'tenant', tenantId: tenant.id, tab: 'overview' }),
    });
    const tabName: Record<string, string> = {
      overview: '개요',
      webauthn: 'WebAuthn',
      aaguid: 'AAGUID 정책',
      apikeys: 'API Keys',
      credentials: 'Credentials',
      audit: 'Audit Logs',
      funnel: 'Funnel',
    };
    items.push({ label: tabName[route.tab] ?? route.tab });
  } else if (route.name === 'activity') {
    items.push({ label: 'Activity' });
  } else if (route.name === 'audit-chain') {
    items.push({ label: 'Audit Chain Monitor' });
  } else if (route.name === 'settings') {
    items.push({ label: '설정' });
  }

  return items;
}

// ── Authenticated shell ──────────────────────────────────────────────────────

function AuthenticatedApp({ me, onLogout }: { me: Me; onLogout: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();

  const route = useMemo(
    () => urlToRoute(location.pathname, new URLSearchParams(location.search)),
    [location.pathname, location.search],
  );

  const setRoute = useCallback((r: AppRoute) => { navigate(routeToUrl(r)); }, [navigate]);

  const [t, setTweak] = useTweaks();
  const [paletteOpen, setPaletteOpen] = useState(false);

  // Cmd/Ctrl+K → open palette
  useEffect(() => {
    function handleKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    }
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, []);

  // Phase E2 에서 tenant 로딩 추가
  const tenant = null;
  const breadcrumb = buildBreadcrumb(route, tenant, me, setRoute);

  async function handleSwitchRole() {
    /* Phase E3 에서 데모용 role 전환 구현 */
  }

  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    if (kind === 'switch-role') void handleSwitchRole();
    if (kind === 'new-tenant') setRoute({ name: 'tenants' });
  }

  return (
    <div
      className="app"
      style={{ display: 'grid', gridTemplateColumns: '232px 1fr', minHeight: '100vh' }}
    >
      <Sidebar
        me={me as any}
        currentRoute={route as any}
        onNavigate={setRoute as any}
        tenant={tenant}
        sidebarMode={t.sidebarMode}
      />
      <div className="content" style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <Header
          me={me as any}
          onLogout={onLogout}
          onSwitchRole={handleSwitchRole}
          breadcrumb={breadcrumb}
          onOpenPalette={() => setPaletteOpen(true)}
        />
        <main style={{ padding: 24 }}>
          <Routes>
            <Route path="/tenants" element={<TenantsListPage />} />
            <Route path="/tenants/:id" element={<TenantDetailRoute me={me} />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route path="/audit-chain" element={<AuditChainPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<Navigate to="/tenants" replace />} />
          </Routes>
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        me={me as any}
        onNavigate={setRoute as any}
        onAction={paletteAction}
      />
      <IdleSessionModal onExtend={() => { /* refresh /me — Phase E3 */ }} onLogout={onLogout} />

      <TweaksPanel title="Tweaks">
        <TweakSection label="테마" />
        <TweakRadio
          label="모드"
          value={t.theme}
          onChange={(v) => setTweak('theme', v as Tweaks['theme'])}
          options={[{ value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }]}
        />
        <TweakColor
          label="Accent"
          value={t.accent}
          onChange={(v) => { const c = Array.isArray(v) ? v[0] : v; if (c) setTweak('accent', c); }}
          options={['#4f46e5', '#5b5bd6', '#7c3aed', '#0f766e', '#db7706']}
        />
        <TweakSection label="레이아웃" />
        <TweakRadio
          label="밀도"
          value={t.density}
          onChange={(v) => setTweak('density', v as Tweaks['density'])}
          options={[{ value: 'compact', label: 'Compact' }, { value: 'comfortable', label: 'Comfort' }]}
        />
        <TweakRadio
          label="테이블"
          value={t.tableStyle}
          onChange={(v) => setTweak('tableStyle', v as Tweaks['tableStyle'])}
          options={[
            { value: 'lines', label: '구분선' },
            { value: 'striped', label: '줄무늬' },
            { value: 'borderless', label: '보더리스' },
          ]}
        />
        <TweakRadio
          label="사이드바"
          value={t.sidebarMode}
          onChange={(v) => setTweak('sidebarMode', v as Tweaks['sidebarMode'])}
          options={[{ value: 'labels', label: '라벨' }, { value: 'icons', label: '아이콘만' }]}
        />
      </TweaksPanel>
    </div>
  );
}

// ── Root App — auth gate ─────────────────────────────────────────────────────

function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get<Me>('/admin/api/me')
      .then(setMe)
      .catch(() => { /* 인증 안됨 — LoginPage 표시 */ })
      .finally(() => setLoading(false));
  }, []);

  async function handleLogout() {
    try { await api.post<void>('/admin/logout', {}); } catch { /* ignore */ }
    setMe(null);
  }

  if (loading) {
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  if (!me) {
    return (
      <ToastHost>
        <LoginPage onLogin={setMe} />
      </ToastHost>
    );
  }

  return (
    <ToastHost>
      <Routes>
        <Route path="*" element={<AuthenticatedApp me={me} onLogout={handleLogout} />} />
      </Routes>
    </ToastHost>
  );
}

export default App;
