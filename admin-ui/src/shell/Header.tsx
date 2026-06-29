import React, { ReactNode, useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { Breadcrumb } from './Breadcrumb';
import { activeTenantApi } from '@/api/activeTenant';
import type { ActiveTenantView } from '@/api/activeTenant';

// ===== MenuItem (private helper) =====
type MenuItemProps = {
  icon: string;
  label: string;
  onClick?: () => void;
};

function MenuItem({ icon, label, onClick }: MenuItemProps) {
  const I = (Icons as Record<string, React.ComponentType<{ size?: number }>>)[icon] || Icons.ChevronRight;
  return (
    <button onClick={onClick} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '8px 10px', border: 0, background: 'transparent', borderRadius: 6, fontSize: 13, color: 'var(--text)', cursor: 'pointer' }}
      onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--surface-3)')}
      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
    >
      <I size={14} />
      <span>{label}</span>
    </button>
  );
}

// ===== RpSwitcher =====
// Shown only when allowedTenantIds.length >= 2 (RP_ADMIN with multiple tenants).
// Fetches tenant names for label display.

type RpSwitcherProps = {
  onSwitch: () => void;
};

function RpSwitcher({ onSwitch }: RpSwitcherProps) {
  const [activeTenant, setActiveTenant] = useState<ActiveTenantView | null>(null);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);

  useEffect(() => {
    activeTenantApi.get()
      .then(setActiveTenant)
      .catch(() => { /* non-critical — hide switcher on error */ });
  }, []);

  // Only show when >= 2 allowed tenants
  if (!activeTenant || activeTenant.allowedTenantIds.length < 2) return null;

  // 라벨 맵은 백엔드 active-tenant 응답의 allowedTenants(id+name)에서 파생한다.
  // RP_ADMIN 은 일반 tenant 목록으로 비활성 RP 를 못 보므로 여기서만 전체 이름을 얻는다.
  const tenantMap: Record<string, string> = {};
  activeTenant.allowedTenants.forEach((t) => { tenantMap[t.id] = t.name; });

  const activeLabel = activeTenant.activeTenantId
    ? (tenantMap[activeTenant.activeTenantId] ?? activeTenant.activeTenantId.slice(-8))
    : '—';

  async function handleSwitch(tenantId: string) {
    if (tenantId === activeTenant?.activeTenantId) { setOpen(false); return; }
    setSwitching(true);
    try {
      await activeTenantApi.switch(tenantId);
      setOpen(false);
      onSwitch();
    } catch {
      // silently ignore — no toast here, caller can add if needed
    } finally {
      setSwitching(false);
    }
  }

  return (
    <div style={{ position: 'relative' }}>
      <button
        className="btn btn--ghost"
        disabled={switching}
        onClick={() => setOpen((v) => !v)}
        style={{ padding: '4px 8px', gap: 6, fontSize: 12 }}
        title="RP 전환"
      >
        <Icons.Building size={14} />
        <span style={{ maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {switching ? '전환 중…' : activeLabel}
        </span>
        <Icons.ChevronDown size={12} />
      </button>
      {open && (
        <>
          <div onMouseDown={() => setOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 30 }} />
          <div style={{
            position: 'absolute', top: '100%', left: 0, marginTop: 6,
            background: 'var(--surface)', border: '1px solid var(--border)',
            borderRadius: 10, boxShadow: 'var(--shadow-lg)',
            minWidth: 220, zIndex: 31, overflow: 'hidden',
          }}>
            <div style={{ padding: '8px 12px', borderBottom: '1px solid var(--border)', fontSize: 11, color: 'var(--text-mute)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              RP 전환
            </div>
            <div style={{ padding: 4 }}>
              {activeTenant.allowedTenantIds.map((id) => {
                const label = tenantMap[id] ?? id.slice(-8);
                const isActive = id === activeTenant.activeTenantId;
                return (
                  <button
                    key={id}
                    onClick={() => void handleSwitch(id)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                      padding: '8px 10px', border: 0, borderRadius: 6,
                      background: isActive ? 'var(--accent-soft)' : 'transparent',
                      color: isActive ? 'var(--accent)' : 'var(--text)',
                      cursor: 'pointer', fontSize: 13, textAlign: 'left',
                    }}
                    onMouseEnter={(e) => { if (!isActive) e.currentTarget.style.background = 'var(--surface-3)'; }}
                    onMouseLeave={(e) => { if (!isActive) e.currentTarget.style.background = 'transparent'; }}
                  >
                    {isActive && <Icons.Check size={12} />}
                    {!isActive && <span style={{ width: 12 }} />}
                    <span style={{ flex: 1 }}>{label}</span>
                  </button>
                );
              })}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// ===== Header =====
type HeaderProps = {
  me: { role: string; email: string; displayName?: string };
  onLogout: () => void;
  onSwitchRole?: () => void;
  breadcrumb: { label: ReactNode; onClick?: () => void }[];
  onOpenPalette: () => void;
  onRpSwitch?: () => void;
};

export function Header({ me, onLogout, onSwitchRole, breadcrumb, onOpenPalette, onRpSwitch }: HeaderProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  return (
    <header style={{
      height: 52, borderBottom: '1px solid var(--border)',
      background: 'var(--surface)', padding: '0 24px',
      display: 'flex', alignItems: 'center', gap: 16,
      position: 'sticky', top: 0, zIndex: 30,
    }}>
      <Breadcrumb items={breadcrumb} />
      <div className="spacer" />

      {/* RP switcher — shown only for RP_ADMIN with ≥2 tenants */}
      {me.role === 'RP_ADMIN' && (
        <RpSwitcher onSwitch={onRpSwitch ?? (() => window.location.reload())} />
      )}

      {/* Global search — opens command palette */}
      <button onClick={onOpenPalette} style={{
        position: 'relative', width: 280, textAlign: 'left',
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '0 8px 0 30px', height: 32,
        border: '1px solid var(--border)', borderRadius: 6,
        background: 'var(--surface)', color: 'var(--text-mute)', cursor: 'pointer',
        fontSize: 12, fontFamily: 'inherit',
      }}>
        <span style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)' }}>
          <Icons.Search size={14} />
        </span>
        <span style={{ flex: 1 }}>tenant, credential, audit ID 검색…</span>
        <span className="kbd">⌘K</span>
      </button>

      {/* User menu */}
      <div style={{ position: 'relative' }}>
        <button className="btn btn--ghost" onClick={() => setMenuOpen((v) => !v)} style={{ padding: '4px 8px', gap: 8 }}>
          <div style={{ width: 26, height: 26, borderRadius: 999, background: 'var(--accent)', color: 'white', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 12 }}>
            {(me.displayName ?? me.email).slice(0, 1)}
          </div>
          <div className="stack-1" style={{ alignItems: 'flex-start', lineHeight: 1.1 }}>
            <div style={{ fontSize: 12, fontWeight: 600 }}>{me.displayName ?? me.email}</div>
            <div style={{ fontSize: 10, color: 'var(--text-mute)' }}>{me.email}</div>
          </div>
          <span className={`badge ${me.role === 'PLATFORM_OPERATOR' ? 'badge--violet' : 'badge--info'}`}>
            {me.role === 'PLATFORM_OPERATOR' ? 'PLATFORM' : 'RP_ADMIN'}
          </span>
          <Icons.ChevronDown size={14} />
        </button>
        {menuOpen && (
          <>
            <div onMouseDown={() => setMenuOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 30 }} />
            <div style={{
              position: 'absolute', top: '100%', right: 0, marginTop: 6,
              background: 'var(--surface)', border: '1px solid var(--border)',
              borderRadius: 10, boxShadow: 'var(--shadow-lg)',
              minWidth: 240, zIndex: 31, overflow: 'hidden',
            }}>
              <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--border)' }}>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{me.displayName ?? me.email}</div>
                <div style={{ fontSize: 12, color: 'var(--text-mute)', marginTop: 2 }}>{me.email}</div>
                <div className="row" style={{ marginTop: 8, gap: 6 }}>
                  <span className={`badge ${me.role === 'PLATFORM_OPERATOR' ? 'badge--violet' : 'badge--info'}`}>{me.role}</span>
                </div>
              </div>
              <div style={{ padding: 6 }}>
                <MenuItem icon="Refresh" label="role 전환 (데모)" onClick={() => { setMenuOpen(false); onSwitchRole?.(); }} />
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
