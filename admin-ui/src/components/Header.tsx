import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Me } from '../api/types';
import { Search } from './Icons';

interface Props {
  onOpenPalette: () => void;
}

const PAGE_TITLES: Record<string, string> = {
  '/tenants': 'Tenants',
  '/tenants/new': 'Tenants',
  '/api-keys': 'API Keys',
  '/keys': 'Signing Keys',
  '/mds': 'MDS Status',
  '/audit': 'Audit Log',
  '/login': 'Sign in',
};

export default function Header({ onOpenPalette }: Props) {
  const loc = useLocation();
  const nav = useNavigate();
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    api.get<Me>('/admin/api/me').then(setMe).catch(() => setMe(null));
  }, []);

  const title = PAGE_TITLES[loc.pathname] ?? loc.pathname.replace(/^\//, '');

  async function logout() {
    const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1];
    await fetch('/admin/logout', {
      method: 'POST',
      credentials: 'include',
      headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {},
    });
    nav('/login');
  }

  return (
    <header style={{
      height: 56,
      display: 'flex',
      alignItems: 'center',
      padding: '0 24px',
      gap: 16,
      borderBottom: '1px solid var(--border-subtle)',
      background: 'var(--surface)',
      position: 'sticky',
      top: 0,
      zIndex: 5,
    }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: 'var(--text)' }}>{title}</div>

      <button
        onClick={onOpenPalette}
        className="row"
        style={{
          marginLeft: 'auto',
          padding: '6px 10px',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius)',
          background: 'var(--surface-2)',
          color: 'var(--text-mute)',
          fontSize: 12,
          minWidth: 260,
          gap: 8,
        }}
      >
        <Search size={14} />
        <span style={{ flex: 1, textAlign: 'left' }}>검색…</span>
        <span className="kbd">⌘K</span>
      </button>

      {me && (
        <div className="row" style={{ gap: 8 }}>
          <div className="stack-1" style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 13, fontWeight: 500 }}>{me.email}</div>
            <div style={{ fontSize: 11, color: 'var(--text-mute)' }}>{me.role}</div>
          </div>
          <button className="btn btn--ghost btn--sm" onClick={logout}>로그아웃</button>
        </div>
      )}
    </header>
  );
}
