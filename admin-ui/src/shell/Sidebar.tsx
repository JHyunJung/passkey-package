import React, { useEffect, useState, useMemo } from 'react';
import { Icons } from '@/icons/Icons';
import { auditChainMonitorApi, type ChainOverview } from '@/api/auditChainMonitor';
import { licenseApi } from '@/api/license';
import { isPlatform } from '@/me/roles';
import type { Me } from '@/api/types';
import type { AppRoute } from '@/appRoute';

function timeAgo(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 1) return '방금';
  if (mins < 60) return `${mins}분 전`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}시간 전`;
}

// ===== Nav items =====
// Top-level route names (AppRoute members with no extra fields). Typed so that
// `onNavigate({ name: item.id })` produces a valid AppRoute.
type TopLevelRouteName = 'tenants' | 'activity' | 'audit-chain' | 'settings' | 'license';

const NAV_PLATFORM: { id: TopLevelRouteName; label: string; icon: string }[] = [
  { id: 'tenants', label: 'Tenants', icon: 'Building' },
  { id: 'activity', label: 'Activity', icon: 'Activity' },
  { id: 'audit-chain', label: 'Audit Chain', icon: 'Hash' },
  { id: 'settings', label: '설정', icon: 'Cog' },
];

const NAV_LICENSE: { id: TopLevelRouteName; label: string; icon: string } = { id: 'license', label: 'License', icon: 'Key' };

const NAV_RP = [
  { id: 'overview', label: '개요', icon: 'Activity' },
  { id: 'webauthn', label: 'WebAuthn', icon: 'Globe' },
  { id: 'aaguid', label: 'AAGUID 정책', icon: 'Shield' },
  { id: 'apikeys', label: 'API Keys', icon: 'Key' },
  { id: 'credentials', label: 'Credentials', icon: 'Fingerprint' },
  { id: 'audit', label: 'Audit Logs', icon: 'Receipt' },
  { id: 'funnel', label: 'Funnel', icon: 'Activity' },
];

// ===== NavBtn (private helper) =====
type NavBtnProps = {
  item: { id: string; label: string; icon: string };
  active: boolean;
  onClick: () => void;
  mode: 'labels' | 'icons' | 'collapsed';
};

function NavBtn({ item, active, onClick, mode }: NavBtnProps) {
  const IconC = (Icons as Record<string, React.ComponentType<{ size?: number }>>)[item.icon] || Icons.Cog;
  return (
    <button
      onClick={onClick}
      title={mode === 'icons' ? item.label : undefined}
      style={{
        display: 'flex', alignItems: 'center', gap: 10,
        width: '100%', padding: mode === 'icons' ? '9px 0' : '8px 10px',
        justifyContent: mode === 'icons' ? 'center' : 'flex-start',
        background: active ? 'var(--accent-soft)' : 'transparent',
        color: active ? 'var(--accent)' : 'var(--text-soft)',
        border: '0', borderRadius: 7,
        fontSize: 13, fontWeight: active ? 600 : 500, cursor: 'pointer',
        textAlign: 'left', marginBottom: 1,
      }}
      onMouseEnter={(e) => { if (!active) e.currentTarget.style.background = 'var(--surface-3)'; }}
      onMouseLeave={(e) => { if (!active) e.currentTarget.style.background = 'transparent'; }}
    >
      <IconC size={16} />
      {mode === 'labels' && <span>{item.label}</span>}
    </button>
  );
}

// ===== Sidebar =====
type SidebarProps = {
  me: Me;
  currentRoute: AppRoute;
  onNavigate: (route: AppRoute) => void;
  tenant?: { id: string; name: string; slug: string } | null;
  sidebarMode?: 'labels' | 'icons' | 'collapsed';
};

export function Sidebar({ me, currentRoute, onNavigate, tenant, sidebarMode = 'labels' }: SidebarProps) {
  const platform = isPlatform(me);
  const [chain, setChain] = useState<ChainOverview | null>(null);
  const [deploymentMode, setDeploymentMode] = useState<'saas' | 'onprem' | null>(null);

  useEffect(() => {
    licenseApi.get()
      .then(v => setDeploymentMode(v.deploymentMode))
      .catch(() => setDeploymentMode('saas'));
  }, []);

  const navItems = useMemo(() => {
    const settingsItem = NAV_PLATFORM[NAV_PLATFORM.length - 1]; // '설정'
    // RP_ADMIN 은 PLATFORM 전역 nav(Tenants/Activity/Audit Chain)를 보지 않지만,
    // 자기 계정(MFA 등)을 위해 '설정' 진입점은 유지한다(/settings 는 RP 도 접근 가능).
    if (!platform) {
      return [settingsItem] as typeof NAV_PLATFORM;
    }
    if (deploymentMode === 'onprem') {
      // Insert License between Audit Chain and Settings
      return [...NAV_PLATFORM.slice(0, -1), NAV_LICENSE, settingsItem];
    }
    return NAV_PLATFORM;
  }, [deploymentMode, platform]);

  useEffect(() => {
    // audit-chain overview 는 PLATFORM 전용 엔드포인트.
    // RP_ADMIN 은 footer(AUDIT CHAIN)를 보지 않으므로 폴링하지 않는다
    // (RP 가 폴링하면 30초마다 403 — 외관엔 영향 없지만 불필요 요청).
    if (!platform) return;
    let cancelled = false;
    async function fetchChain() {
      try {
        const o = await auditChainMonitorApi.overview(24);
        if (!cancelled) setChain(o);
      } catch { /* 실패 시 carousel 정적 표시 유지 */ }
    }
    fetchChain();
    const id = setInterval(fetchChain, 30_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [platform]);
  // Build a contextual nav: when inside a tenant, show tenant-tab nav under the tenant name.
  return (
    <aside style={{
      gridArea: 'sidebar',
      background: 'var(--surface)',
      borderRight: '1px solid var(--border)',
      display: 'flex',
      flexDirection: 'column',
      position: 'sticky',
      top: 0,
      height: '100vh',
      overflow: 'hidden',
    }}>
      <div style={{ padding: sidebarMode === 'icons' ? '16px 8px' : '16px 18px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
        <Icons.BrandMark size={26} />
        {sidebarMode === 'labels' && (
          <div className="stack-1" style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 13, lineHeight: 1.2, letterSpacing: '-0.01em' }}>Passkey Admin</div>
            <div style={{ fontSize: 11, color: 'var(--text-mute)', lineHeight: 1.2 }}>Crosscert · prod</div>
          </div>
        )}
      </div>

      {/* Tenant context block */}
      {tenant && sidebarMode === 'labels' && (
        <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--border)' }}>
          {platform && (
            <button className="btn btn--ghost btn--xs" onClick={() => onNavigate({ name: 'tenants' })} style={{ marginBottom: 6, padding: '2px 4px', color: 'var(--text-mute)' }}>
              <Icons.ChevronLeft size={12} /> Tenants
            </button>
          )}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ width: 24, height: 24, borderRadius: 6, background: 'var(--accent-soft)', color: 'var(--accent)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 11 }}>
              {tenant.name.slice(0, 1)}
            </div>
            <div className="stack-1" style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 600, fontSize: 13, letterSpacing: '-0.01em', lineHeight: 1.1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{tenant.name}</div>
              <div className="mono" style={{ fontSize: 11, color: 'var(--text-mute)', lineHeight: 1.1 }}>{tenant.slug}</div>
            </div>
          </div>
        </div>
      )}

      <nav style={{ padding: '10px 8px', flex: 1, overflow: 'auto' }}>
        {tenant ? (
          NAV_RP.map((item) => <NavBtn key={item.id} item={item} active={currentRoute.name === 'tenant' && currentRoute.tab === item.id} mode={sidebarMode ?? 'labels'} onClick={() => onNavigate({ name: 'tenant', tenantId: tenant.id, tab: item.id })} />)
        ) : (
          navItems.map((item) => <NavBtn key={item.id} item={item} active={currentRoute.name === item.id} mode={sidebarMode ?? 'labels'} onClick={() => onNavigate({ name: item.id })} />)
        )}
      </nav>

      {/* Footer: audit chain status */}
      {sidebarMode === 'labels' && (
        <div style={{ padding: '10px 14px', borderTop: '1px solid var(--border)' }}>
          {!chain?.totals ? (
            <div className="stack-1" style={{ padding: '8px 10px', background: 'var(--success-soft)', borderRadius: 8, border: '1px solid color-mix(in oklab, var(--success) 20%, transparent)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 600, color: 'var(--success)' }}>
                <span style={{ width: 6, height: 6, borderRadius: 999, background: 'var(--success)', boxShadow: '0 0 0 3px color-mix(in oklab, var(--success) 25%, transparent)' }}></span>
                AUDIT CHAIN
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>확인 중…</div>
            </div>
          ) : chain.totals.tenantsTampered > 0 ? (
            <div className="stack-1" style={{ padding: '8px 10px', background: 'var(--danger-soft, #fff0f0)', borderRadius: 8, border: '1px solid color-mix(in oklab, var(--danger, red) 20%, transparent)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 600, color: 'var(--danger, red)' }}>
                <span style={{ width: 6, height: 6, borderRadius: 999, background: 'var(--danger, red)', boxShadow: '0 0 0 3px color-mix(in oklab, var(--danger, red) 25%, transparent)' }}></span>
                위변조 의심
              </div>
              <div style={{ fontSize: 11, color: 'var(--danger, red)' }}>{chain.totals.tenantsTampered}/{chain.totals.tenantsTotal} tenant</div>
            </div>
          ) : (
            <div className="stack-1" style={{ padding: '8px 10px', background: 'var(--success-soft)', borderRadius: 8, border: '1px solid color-mix(in oklab, var(--success) 20%, transparent)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 600, color: 'var(--success)' }}>
                <span style={{ width: 6, height: 6, borderRadius: 999, background: 'var(--success)', boxShadow: '0 0 0 3px color-mix(in oklab, var(--success) 25%, transparent)' }}></span>
                AUDIT CHAIN OK
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>마지막 검증 · {timeAgo(chain.verifiedAt)} · {chain.totals.verifiedRows.toLocaleString()} 행</div>
            </div>
          )}
        </div>
      )}
    </aside>
  );
}
