import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within, act } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { ApiKey, Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), rotate: vi.fn(), revoke: vi.fn() }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: api }));

import ApiKeysTab from './ApiKeysTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const DAY = 86_400_000;
const iso = (days: number) => new Date(Date.now() + days * DAY).toISOString();
const key = (p: Partial<ApiKey>): ApiKey => ({
  id: 'k', prefix: 'pk_live_ab', name: 'prod', status: 'ACTIVE', createdAt: iso(-10), lastUsedAt: null,
  scopes: ['registration'], expiresAt: null, ...p,
});
const renderTab = (onChanged?: () => void) => renderWithDataRouter(<ToastHost><ApiKeysTab tenant={tenant} onChanged={onChanged} /></ToastHost>, { initialPath: '/tenants/t1?tab=apikeys' });

describe('ApiKeysTab V2', () => {
  beforeEach(() => vi.clearAllMocks());

  it('⋯ 메뉴는 ACTIVE 키에만 있고, 회수는 위험 구획에서 열리며 취소에 기본 포커스', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'prod' }),
      key({ id: 'r', name: 'old', status: 'REVOKED' }),
      key({ id: 'e', name: 'exp', status: 'EXPIRED', expiresAt: iso(-1) }),
    ]);
    renderTab();
    expect(await screen.findByText('prod')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'prod 키 액션' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'old 키 액션' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'exp 키 액션' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'prod 키 액션' }));
    expect(screen.getByRole('menuitem', { name: '회전' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: '접두사 복사' })).toBeInTheDocument();
    expect(screen.getByText('위험')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));

    const dlg = screen.getByRole('alertdialog', { name: 'API key를 회수하시겠습니까?' });
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByRole('button', { name: '회수' })).toHaveClass('btn--danger-solid');
  });

  it('키 접두사 뒤 keySuffix 는 값이 있을 때만 붙는다', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'with', keySuffix: 'a1c9' }),
      key({ id: 'b', name: 'without', prefix: 'pk_live_cd' }),
    ]);
    renderTab();
    expect(await screen.findByText('pk_live_ab…a1c9')).toBeInTheDocument();
    expect(screen.getByText('pk_live_cd…')).toBeInTheDocument();
  });

  it('30일 이내 만료 ACTIVE 키는 남은 일수를 경고로 표시, KPI 에 rotation 초과 키 수', async () => {
    api.list.mockResolvedValue([
      key({ id: 'a', name: 'soon', expiresAt: iso(10) }),
      key({ id: 'b', name: 'stale', createdAt: iso(-100) }),
      key({ id: 'c', name: 'gone', status: 'REVOKED', createdAt: iso(-200) }),
    ]);
    renderTab();
    expect(await screen.findByText(/· 10일 남음/)).toBeInTheDocument();
    expect(screen.getByText('초과 키 1개')).toHaveClass('kpi__hint--warn');
    expect(screen.getByText('활성 2 · 만료 0 · 회수 1')).toBeInTheDocument();
  });

  it('발급 완료 다이얼로그: Escape·스크림 무시, 체크 전 닫기 비활성', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockResolvedValue({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));

    const dlg = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(api.create).toHaveBeenCalledWith('t1', 'staging', ['registration', 'authentication'], 24);
    expect(within(dlg).getByText('pk_live_ab.secret')).toBeInTheDocument();
    const close = within(dlg).getByRole('button', { name: /닫기 \(영구 소실\)/ });
    expect(close).toBeDisabled();

    fireEvent.keyDown(window, { key: 'Escape' });
    fireEvent.mouseDown(document.querySelector('.scrim')!);
    expect(screen.getByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();

    fireEvent.click(within(dlg).getByRole('checkbox', { name: '안전한 장소에 복사했습니다.' }));
    expect(close).toBeEnabled();
    expect(close).toHaveTextContent(/^닫기 \(영구 소실\)$/);
    fireEvent.click(close);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('회전: 24시간 겹침 안내 후 실행하면 새 키와 이전 키 만료 시간을 보여준다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.rotate.mockResolvedValue({ id: 'a2', plaintextKey: 'pk_live_ab.new', prefix: 'pk_live_ab', scopes: ['registration'], oldKeyExpiresAt: iso(1) });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    const rot = screen.getByRole('dialog', { name: 'API key를 회전하시겠습니까?' });
    expect(within(rot).getByText('24시간 함께 유효')).toBeInTheDocument();
    fireEvent.click(within(rot).getByRole('button', { name: '회전 실행' }));
    const issued = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(api.rotate).toHaveBeenCalledWith('a');
    expect(within(issued).getByText(/이전 키는 24시간 후/)).toBeInTheDocument();
  });

  it('발급 다이얼로그가 열린 동안 이동·새로고침을 막는다(평문 키 소실 방지)', async () => {
    api.list.mockResolvedValue([]);
    api.create.mockResolvedValue({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' });
    const { router } = renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    fireEvent.click(screen.getByRole('button', { name: '발급' }));
    await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });

    const ev = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);

    await act(async () => { await router.navigate('/tenants'); });
    expect(screen.getByRole('alertdialog', { name: '키를 아직 저장하지 않았습니다' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tenants/t1');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(screen.getByText('pk_live_ab.secret')).toBeInTheDocument();
  });

  it('회전 다이얼로그는 새 키의 id 를 보이고, 만료는 새 키를 다시 읽은 값(이전 키 만료일 아님)', async () => {
    const oldKey = key({ id: 'a', name: 'prod', expiresAt: iso(5) });
    const newKey = key({ id: 'a2', name: 'prod', expiresAt: '2028-10-11T00:00:00Z' });
    api.list.mockResolvedValueOnce([oldKey]).mockResolvedValue([oldKey, newKey]);
    api.rotate.mockResolvedValue({ id: 'a2', plaintextKey: 'pk_live_ab.new', prefix: 'pk_live_ab', scopes: ['registration'], oldKeyExpiresAt: iso(1) });
    const onChanged = vi.fn();
    renderTab(onChanged);
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회전' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'API key를 회전하시겠습니까?' })).getByRole('button', { name: '회전 실행' }));
    const issued = await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ });
    expect(within(issued).getByText('a2')).toBeInTheDocument();
    expect(within(issued).queryByText(/^a$/)).toBeNull();
    expect(await within(issued).findByText('2028-10-11')).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });

  it('발급 버튼 연타는 키를 한 번만 발급한다', async () => {
    api.list.mockResolvedValue([]);
    let resolve: (v: unknown) => void = () => {};
    api.create.mockReturnValue(new Promise((r) => { resolve = r; }));
    const onChanged = vi.fn();
    renderTab(onChanged);
    fireEvent.click(await screen.findByRole('button', { name: '새 키 발급' }));
    fireEvent.change(screen.getByLabelText('용도 (이름)'), { target: { value: 'staging' } });
    const issue = screen.getByRole('button', { name: '발급' });
    fireEvent.click(issue);
    fireEvent.click(issue);
    expect(api.create).toHaveBeenCalledTimes(1);
    await act(async () => { resolve({ key: key({ id: 'n1', name: 'staging' }), plaintext: 'pk_live_ab.secret' }); });
    expect(await screen.findByRole('alertdialog', { name: /새 API key가 발급되었습니다/ })).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });

  it('회수·회전 확인 버튼도 처리 중에는 다시 누를 수 없다', async () => {
    api.list.mockResolvedValue([key({ id: 'a', name: 'prod' })]);
    api.revoke.mockReturnValue(new Promise(() => {}));
    api.rotate.mockReturnValue(new Promise(() => {}));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'prod 키 액션' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '회수' }));
    const revokeBtn = within(screen.getByRole('alertdialog')).getByRole('button', { name: '회수' });
    fireEvent.click(revokeBtn);
    fireEvent.click(revokeBtn);
    expect(api.revoke).toHaveBeenCalledTimes(1);
    expect(revokeBtn).toBeDisabled();
  });
});
