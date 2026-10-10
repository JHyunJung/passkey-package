import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
vi.mock('@/api/activeTenant', () => ({ activeTenantApi: { get: vi.fn().mockResolvedValue(null), switch: vi.fn() } }));
import { Header } from './Header';

describe('Header V2', () => {
  it('브레드크럼 마지막 항목은 현재 위치, 검색 버튼은 팔레트를 연다', () => {
    const onOpenPalette = vi.fn();
    render(<Header me={{ role: 'PLATFORM_OPERATOR', email: 'a@x.com' }} onLogout={() => {}} breadcrumb={[{ label: 'Tenants', onClick: () => {} }, { label: 'Acme' }]} onOpenPalette={onOpenPalette} />);
    expect(screen.getByText('Acme')).toHaveAttribute('aria-current', 'page');
    fireEvent.click(screen.getByRole('button', { name: /검색/ }));
    expect(onOpenPalette).toHaveBeenCalled();
    expect(screen.getByText('PLATFORM')).toBeInTheDocument();
  });
  it('사용자 메뉴는 로그아웃만 — 동작하지 않는 role 전환(데모)·API 문서·도움말을 보이지 않는다', () => {
    const onLogout = vi.fn();
    render(<Header me={{ role: 'PLATFORM_OPERATOR', email: 'a@x.com' }} onLogout={onLogout} breadcrumb={[]} onOpenPalette={() => {}} />);
    expect(screen.queryByRole('button', { name: /도움말/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '사용자 메뉴' }));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['로그아웃']);
    fireEvent.click(screen.getByRole('menuitem', { name: '로그아웃' }));
    expect(onLogout).toHaveBeenCalled();
  });
});
