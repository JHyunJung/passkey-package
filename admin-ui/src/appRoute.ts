// ── Route type (mirrors design app.jsx shape) ────────────────────────────────
// Shared so shell/extras components can type their nav props against AppRoute
// without importing App.tsx (which would create a circular import).

export type AppRoute =
  | { name: 'tenants' }
  | { name: 'tenant'; tenantId: string; tab: string }
  | { name: 'activity' }
  | { name: 'audit-chain' }
  | { name: 'settings' }
  | { name: 'license' };

export function urlToRoute(pathname: string, search: URLSearchParams): AppRoute {
  if (pathname.startsWith('/tenants/')) {
    const id = pathname.split('/')[2];
    return { name: 'tenant', tenantId: id, tab: search.get('tab') || 'overview' };
  }
  if (pathname === '/activity') return { name: 'activity' };
  if (pathname === '/audit-chain') return { name: 'audit-chain' };
  if (pathname === '/settings') return { name: 'settings' };
  if (pathname === '/license') return { name: 'license' };
  return { name: 'tenants' };
}

export function routeToUrl(r: AppRoute): string {
  if (r.name === 'tenants') return '/tenants';
  if (r.name === 'tenant') return `/tenants/${r.tenantId}?tab=${r.tab}`;
  if (r.name === 'activity') return '/activity';
  if (r.name === 'audit-chain') return '/audit-chain';
  if (r.name === 'license') return '/license';
  return '/settings';
}
