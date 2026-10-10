import { useState, useEffect, useMemo, useCallback, Fragment } from 'react';
import { LicenseBanner } from '@/components/LicenseBanner';
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';
import TenantsListPage from '@/pages/TenantsListPage';
import TenantDetailRoute from '@/pages/TenantDetailPage';
import { ToastHost } from '@/ui';
import { Sidebar } from '@/shell/Sidebar';
import { ErrorBoundary } from '@/shell/ErrorBoundary';
import { Header } from '@/shell/Header';
import { IdleSessionModal } from '@/extras/IdleSessionModal';
import { CommandPalette } from '@/extras/CommandPalette';
import { useGoShortcuts } from '@/extras/useGoShortcuts';
import { usePaletteShortcut } from '@/extras/usePaletteShortcut';
import { isPlatform } from '@/me/roles';
import LoginPage from '@/pages/LoginPage';
import MfaChallenge from '@/pages/MfaChallenge';
import ActivityPage from '@/pages/ActivityPage';
import AuditChainPage from '@/pages/AuditChainPage';
import SettingsPage from '@/pages/SettingsPage';
import LicensePage from '@/pages/LicensePage';
import ForgotPasswordPage from '@/pages/ForgotPasswordPage';
import ResetPasswordPage from '@/pages/ResetPasswordPage';
import SignupRequestPage from '@/pages/SignupRequestPage';
import { api, getMe } from '@/api/client';
import type { Me } from '@/api/types';
import type { AppRoute } from '@/appRoute';
import { urlToRoute, routeToUrl } from '@/appRoute';
import { RequirePlatform } from '@/me/RequirePlatform';
import { rpTenantId } from '@/me/roles';
import { useCrumbTenant } from '@/shell/useCrumbTenant';

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
      apikeys: 'API 키',
      credentials: 'Credentials',
      audit: '감사 로그',
      funnel: '퍼널',
    };
    items.push({ label: tabName[route.tab] ?? route.tab });
  } else if (route.name === 'activity') {
    items.push({ label: '활동' });
  } else if (route.name === 'audit-chain') {
    items.push({ label: '감사 체인 모니터' });
  } else if (route.name === 'settings') {
    items.push({ label: '설정' });
  } else if (route.name === 'license') {
    items.push({ label: '라이선스' });
  }

  return items;
}

// ── Authenticated shell ──────────────────────────────────────────────────────

function AuthenticatedApp({ me, onLogout, onMeChange }: { me: Me; onLogout: () => void; onMeChange: (m: Me) => void }) {
  const location = useLocation();
  const navigate = useNavigate();

  const route = useMemo(
    () => urlToRoute(location.pathname, new URLSearchParams(location.search)),
    [location.pathname, location.search],
  );

  const setRoute = useCallback((r: AppRoute) => { navigate(routeToUrl(r)); }, [navigate]);

  const [paletteOpen, setPaletteOpen] = useState(false);

  // Cmd/Ctrl+K → open palette (비밀값 alertdialog 가 열려 있으면 무시)
  const togglePalette = useCallback(() => setPaletteOpen((v) => !v), []);
  usePaletteShortcut(togglePalette);

  useGoShortcuts(setRoute, isPlatform(me));

  const tenant = useCrumbTenant(route.name === 'tenant' ? route.tenantId : null);
  const breadcrumb = buildBreadcrumb(route, tenant, me, setRoute);

  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    if (kind === 'new-tenant') setRoute({ name: 'tenants' });
  }

  return (
    <Fragment>
      <LicenseBanner />
      <div className="app">
        <ErrorBoundary fallback={null}>
          <Sidebar me={me} currentRoute={route} onNavigate={setRoute} />
        </ErrorBoundary>
        <div className="content">
          <Header
            me={me}
            onLogout={onLogout}
            breadcrumb={breadcrumb}
            onOpenPalette={() => setPaletteOpen(true)}
          />
          <main>
            <ErrorBoundary>
              <Routes>
                <Route path="/tenants" element={<RequirePlatform me={me}><TenantsListPage /></RequirePlatform>} />
                <Route path="/tenants/:id" element={<TenantDetailRoute me={me} />} />
                <Route path="/activity" element={<RequirePlatform me={me}><ActivityPage /></RequirePlatform>} />
                <Route path="/audit-chain" element={<RequirePlatform me={me}><AuditChainPage /></RequirePlatform>} />
                <Route path="/settings" element={<SettingsPage me={me} onMeChange={onMeChange} />} />
                <Route path="/license" element={<RequirePlatform me={me}><LicensePage /></RequirePlatform>} />
                <Route path="*" element={<Navigate to={rpTenantId(me) ? `/tenants/${rpTenantId(me)}` : '/tenants'} replace />} />
              </Routes>
            </ErrorBoundary>
          </main>
        </div>

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        me={me}
        onNavigate={setRoute}
        onAction={paletteAction}
      />
      <IdleSessionModal
        idleTimeoutMinutes={me.sessionIdleTimeoutMinutes}
        onExtend={() => { getMe().catch(() => { /* 401은 client가 로그인으로 redirect 처리 */ }); }}
        onLogout={onLogout}
      />

      </div>
    </Fragment>
  );
}

// ── Root App — auth gate ─────────────────────────────────────────────────────

function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const location = useLocation();
  const isPublicPath = location.pathname === '/forgot-password' || location.pathname === '/reset-password' || location.pathname === '/signup';

  useEffect(() => {
    if (isPublicPath) { setLoading(false); return; }
    api.get<Me>('/admin/api/me')
      .then(setMe)
      .catch(() => { /* 인증 안됨 — LoginPage 표시 */ })
      .finally(() => setLoading(false));
  }, [isPublicPath]);

  // public 경로 — me 로딩과 무관하게 즉시 렌더
  if (location.pathname === '/forgot-password') return <ForgotPasswordPage />;
  if (location.pathname === '/reset-password') return <ResetPasswordPage />;
  if (location.pathname === '/signup') return <SignupRequestPage />;

  async function handleLogout() {
    try { await api.post<void>('/admin/logout', {}); } catch { /* ignore */ }
    setMe(null);
  }

  async function reloadMe() {
    try { setMe(await api.get<Me>('/admin/api/me')); }
    catch { setMe(null); }
  }

  if (loading) {
    return <div style={{ padding: 40, color: 'var(--mute)' }}>Loading…</div>;
  }

  if (!me) {
    return (
      <ToastHost>
        <LoginPage onLogin={setMe} />
      </ToastHost>
    );
  }

  if (me.mfaRequired) {
    return (
      <ToastHost>
        <MfaChallenge onVerified={reloadMe} onLogout={handleLogout} />
      </ToastHost>
    );
  }

  return (
    <ToastHost>
      <Routes>
        <Route path="*" element={<AuthenticatedApp me={me} onLogout={handleLogout} onMeChange={setMe} />} />
      </Routes>
    </ToastHost>
  );
}

export default App;
