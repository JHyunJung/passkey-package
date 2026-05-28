import React, { ReactNode, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Breadcrumb } from './Breadcrumb';

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

// ===== Header =====
type HeaderProps = {
  me: { role: string; email: string; displayName?: string };
  onLogout: () => void;
  onSwitchRole?: () => void;
  breadcrumb: { label: ReactNode; onClick?: () => void }[];
  onOpenPalette: () => void;
};

export function Header({ me, onLogout, onSwitchRole, breadcrumb, onOpenPalette }: HeaderProps) {
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
