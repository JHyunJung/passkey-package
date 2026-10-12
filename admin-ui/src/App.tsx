import { useState, useEffect, useMemo, useCallback, useRef, Fragment } from 'react';
import { LicenseBanner } from '@/components/LicenseBanner';
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';
import TenantsListPage from '@/pages/TenantsListPage';
import TenantDetailRoute from '@/pages/TenantDetailPage';
import { ToastHost, Skeleton } from '@/ui';
import { Sidebar } from '@/shell/Sidebar';
import { ErrorBoundary } from '@/shell/ErrorBoundary';
import { Header } from '@/shell/Header';
import { IdleSessionModal } from '@/extras/IdleSessionModal';
import { CommandPalette } from '@/extras/CommandPalette';
import { useGoShortcuts } from '@/extras/useGoShortcuts';
import { usePaletteShortcut } from '@/extras/usePaletteShortcut';
import { useHelpShortcut } from '@/extras/useHelpShortcut';
import { ShortcutSheet } from '@/extras/ShortcutSheet';
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

  // ? → 키보드 단축키 시트 (입력 중·모달 열림이면 무시)
  const [sheetOpen, setSheetOpen] = useState(false);
  const openSheet = useCallback(() => setSheetOpen(true), []);
  useHelpShortcut(openSheet);

  const tenant = useCrumbTenant(route.name === 'tenant' ? route.tenantId : null);
  const breadcrumb = buildBreadcrumb(route, tenant, me, setRoute);

  function paletteAction(kind: string) {
    if (kind === 'logout') onLogout();
    // 목록으로 보내고 끝내지 않고 생성 다이얼로그까지 연다(TenantsListPage 가 ?new=1 을 처리).
    if (kind === 'new-tenant') navigate('/tenants?new=1');
    if (kind === 'shortcuts') openSheet();
  }

  return (
    <Fragment>
      <LicenseBanner showLink={isPlatform(me)} />
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
            onOpenShortcuts={openSheet}
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
      <ShortcutSheet open={sheetOpen} onClose={() => setSheetOpen(false)} platform={isPlatform(me)} />
      <IdleSessionModal
        idleTimeoutMinutes={me.sessionIdleTimeoutMinutes}
        onExtend={async () => { await getMe(); }}
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
  // 인증 세대. 로그아웃마다 올린다. 비동기 경로의 setMe 는 시작할 때의 세대가 아직 현재일 때만 반영한다 —
  // 로그아웃 전에 시작한 /me 응답이 늦게 와서 콘솔을 다시 여는 일을 막는다.
  const authEpoch = useRef(0);
  /** 지금 세대에 묶인 setMe. 세대가 바뀐 뒤(로그아웃 뒤) 호출되면 버린다. */
  function setMeForEpoch(epoch: number) {
    return (m: Me | null) => { if (epoch === authEpoch.current) setMe(m); };
  }
  const location = useLocation();
  const isPublicPath = location.pathname === '/forgot-password' || location.pathname === '/reset-password' || location.pathname === '/signup';

  useEffect(() => {
    if (isPublicPath) { setLoading(false); return; }
    const apply = setMeForEpoch(authEpoch.current);
    api.get<Me>('/admin/api/me')
      .then(apply)
      .catch(() => { /* 인증 안됨 — LoginPage 표시 */ })
      .finally(() => setLoading(false));
  }, [isPublicPath]);

  // public 경로 — me 로딩과 무관하게 즉시 렌더
  if (location.pathname === '/forgot-password') return <ForgotPasswordPage />;
  if (location.pathname === '/reset-password') return <ResetPasswordPage />;
  if (location.pathname === '/signup') return <SignupRequestPage />;

  // 로컬 인증 상태를 먼저 지워 콘솔을 즉시 닫는다. 서버 로그아웃은 뒤에서 보낸다 —
  // 응답이 늦거나 없어도 화면이 열린 채 남거나 유휴 모달이 다시 뜨지 않게.
  function handleLogout() {
    authEpoch.current++;
    setMe(null);
    api.post<void>('/admin/logout', {}).catch(() => { /* 세션은 서버에서도 곧 만료된다 */ });
  }

  async function reloadMe() {
    const apply = setMeForEpoch(authEpoch.current);
    try { apply(await api.get<Me>('/admin/api/me')); }
    catch { apply(null); }
  }
  // 하위 화면에 넘기는 setMe 도 렌더 시점의 세대에 묶는다(로그인·계정 화면의 비동기 갱신).
  const setMeCurrent = setMeForEpoch(authEpoch.current);

  if (loading) {
    return <div style={{ padding: 40 }}><Skeleton /></div>;
  }

  if (!me) {
    return (
      <ToastHost>
        <LoginPage onLogin={setMeCurrent} />
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
        <Route path="*" element={<AuthenticatedApp me={me} onLogout={handleLogout} onMeChange={setMeCurrent} />} />
      </Routes>
    </ToastHost>
  );
}

export default App;
