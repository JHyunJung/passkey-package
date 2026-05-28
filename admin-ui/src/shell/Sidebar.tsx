import React from 'react';
import { Icons } from '@/icons/Icons';

// ===== Nav items =====
const NAV_PLATFORM = [
  { id: 'tenants', label: 'Tenants', icon: 'Building' },
  { id: 'activity', label: 'Activity', icon: 'Activity' },
  { id: 'audit-chain', label: 'Audit Chain', icon: 'Hash' },
  { id: 'settings', label: '설정', icon: 'Cog' },
];

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
  me: { role: string; tenantId?: string | null; email: string; displayName?: string };
  currentRoute: { name: string; tenantId?: string; tab?: string };
  onNavigate: (route: { name: string; tenantId?: string; tab?: string }) => void;
  tenant?: { id: string; name: string; slug: string } | null;
  sidebarMode?: 'labels' | 'icons' | 'collapsed';
};

export function Sidebar({ me, currentRoute, onNavigate, tenant, sidebarMode = 'labels' }: SidebarProps) {
  const isPlatform = me.role === 'PLATFORM_OPERATOR';
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
          {isPlatform && (
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
          NAV_RP.map((item) => <NavBtn key={item.id} item={item} active={currentRoute.tab === item.id} mode={sidebarMode ?? 'labels'} onClick={() => onNavigate({ name: 'tenant', tenantId: tenant.id, tab: item.id })} />)
        ) : (
          NAV_PLATFORM.map((item) => <NavBtn key={item.id} item={item} active={currentRoute.name === item.id} mode={sidebarMode ?? 'labels'} onClick={() => onNavigate({ name: item.id })} />)
        )}
      </nav>

      {/* Footer: audit chain status (v1.1 sneak peek) */}
      {sidebarMode === 'labels' && (
        <div style={{ padding: '10px 14px', borderTop: '1px solid var(--border)' }}>
          <div className="stack-1" style={{ padding: '8px 10px', background: 'var(--success-soft)', borderRadius: 8, border: '1px solid color-mix(in oklab, var(--success) 20%, transparent)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 600, color: 'var(--success)' }}>
              <span style={{ width: 6, height: 6, borderRadius: 999, background: 'var(--success)', boxShadow: '0 0 0 3px color-mix(in oklab, var(--success) 25%, transparent)' }}></span>
              AUDIT CHAIN OK
            </div>
            <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>마지막 검증 · 2분 전 · 1,284,920 행</div>
          </div>
        </div>
      )}
    </aside>
  );
}
