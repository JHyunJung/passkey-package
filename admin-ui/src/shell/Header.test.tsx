import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup } from '@testing-library/react';

const links = vi.hoisted(() => ({ apiDocs: '', rpGuide: '', support: '' }));
vi.mock('@/config/links', () => ({ HELP_LINKS: links }));
vi.mock('@/api/activeTenant', () => ({ activeTenantApi: { get: vi.fn().mockResolvedValue(null), switch: vi.fn() } }));

import { activeTenantApi } from '@/api/activeTenant';
import { ApiError } from '@/api/types';
import { ToastHost } from '@/ui';
import { Header } from './Header';

type Props = Parameters<typeof Header>[0];
function renderHeader(over: Partial<Props> = {}) {
  const props: Props = {
    me: { role: 'PLATFORM_OPERATOR', email: 'a@x.com' },
    onLogout: vi.fn(), breadcrumb: [], onOpenPalette: vi.fn(), onOpenShortcuts: vi.fn(),
    ...over,
  };
  render(<ToastHost><Header {...props} /></ToastHost>);
  return props;
}

afterEach(() => { Object.assign(links, { apiDocs: '', rpGuide: '', support: '' }); });

describe('Header V3', () => {
  it('브레드크럼 마지막 항목은 현재 위치, 검색 버튼은 실제 검색 범위를 말하고 팔레트를 연다', () => {
    const p = renderHeader({ breadcrumb: [{ label: 'Tenants', onClick: () => {} }, { label: 'Acme' }] });
    expect(screen.getByText('Acme')).toHaveAttribute('aria-current', 'page');
    const search = screen.getByRole('button', { name: /검색/ });
    expect(search).toHaveTextContent('tenant 이름 · slug 검색');
    expect(search).not.toHaveTextContent('credential');
    fireEvent.click(search);
    expect(p.onOpenPalette).toHaveBeenCalled();
    expect(screen.getByText('PLATFORM')).toBeInTheDocument();
  });

  it('검색 단축키 표기는 OS 별: Mac ⌘K, 그 밖 Ctrl K', () => {
    const spy = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel');
    renderHeader();
    expect(screen.getByRole('button', { name: /검색/ }).querySelector('kbd')).toHaveTextContent('⌘K');
    cleanup();
    spy.mockReturnValue('Win32');
    renderHeader();
    expect(screen.getByRole('button', { name: /검색/ }).querySelector('kbd')).toHaveTextContent('Ctrl K');
    spy.mockRestore();
  });

  it('사용자 메뉴는 로그아웃만 — 동작하지 않는 역할 전환(데모)은 없다. 도움말은 별도 메뉴', () => {
    const p = renderHeader();
    expect(screen.getByRole('button', { name: /도움말/ })).toHaveAttribute('aria-haspopup', 'menu');
    fireEvent.click(screen.getByRole('button', { name: '사용자 메뉴' }));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['로그아웃']);
    fireEvent.click(screen.getByRole('menuitem', { name: '로그아웃' }));
    expect(p.onLogout).toHaveBeenCalled();
  });

  it('도움말: 주소가 비어 있으면 문서 항목을 숨기고 키보드 단축키만, 누르면 시트를 연다', () => {
    const p = renderHeader();
    fireEvent.click(screen.getByRole('button', { name: /도움말/ }));
    const menu = screen.getByRole('menu', { name: '도움말' });
    expect(within(menu).getAllByRole('menuitem')).toHaveLength(1);
    const item = within(menu).getByRole('menuitem', { name: '키보드 단축키' });
    expect(item).toHaveAttribute('aria-keyshortcuts', '?');
    expect(document.activeElement).toBe(item);
    fireEvent.click(item);
    expect(p.onOpenShortcuts).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu', { name: '도움말' })).toBeNull();
  });

  it('도움말: 주소가 있으면 API 문서·RP 연동 가이드는 새 창, 운영자 문의 링크', () => {
    Object.assign(links, { apiDocs: 'https://docs.example/api', rpGuide: 'https://docs.example/rp', support: 'mailto:ops@example.com' });
    renderHeader();
    fireEvent.click(screen.getByRole('button', { name: /도움말/ }));
    const apiDocs = screen.getByRole('menuitem', { name: /API 문서 열기/ });
    expect(apiDocs).toHaveAttribute('href', 'https://docs.example/api');
    expect(apiDocs).toHaveAttribute('target', '_blank');
    expect(apiDocs).toHaveAttribute('rel', 'noopener noreferrer');
    expect(apiDocs).toHaveTextContent('새 창');
    expect(screen.getByRole('menuitem', { name: /RP 연동 가이드/ })).toHaveAttribute('href', 'https://docs.example/rp');
    expect(screen.getByRole('menuitem', { name: '운영자에게 문의' })).toHaveAttribute('href', 'mailto:ops@example.com');
  });

  it('RP 전환이 실패하면 오류 토스트를 띄우고 화면은 그대로 둔다', async () => {
    vi.mocked(activeTenantApi.get).mockResolvedValueOnce({
      activeTenantId: 't1', allowedTenantIds: ['t1', 't2'],
      allowedTenants: [{ id: 't1', name: 'Acme' }, { id: 't2', name: 'Beta' }],
    });
    vi.mocked(activeTenantApi.switch).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    const onRpSwitch = vi.fn();
    renderHeader({ me: { role: 'RP_ADMIN', email: 'b@x.com' }, onRpSwitch });
    fireEvent.click(await screen.findByRole('button', { name: /Acme/ }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Beta' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('RP를 전환하지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('Acme');
    expect(onRpSwitch).not.toHaveBeenCalled();
  });
});
