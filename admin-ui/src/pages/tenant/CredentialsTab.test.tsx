// admin-ui/src/pages/tenant/CredentialsTab.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { Credential, Tenant } from '@/api/designTypes';
import { ApiError } from '@/api/types';

const api = vi.hoisted(() => ({ list: vi.fn(), revoke: vi.fn(), authEvents: vi.fn() }));
vi.mock('@/api/credentials', () => ({ credentialsApi: api }));

import CredentialsTab from './CredentialsTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 3, apiKeys: 0,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const cred = (id: string, p: Partial<Credential> = {}): Credential => ({
  credentialId: id, externalUserId: `user-${id}`, nickname: null, authenticatorName: 'iCloud Keychain', status: 'ACTIVE',
  aaguid: 'fbfc3007-154e-4ecc-8c0b-6e020557d7bd', transports: ['internal'], signatureCounter: 0, lastUsedAt: null,
  createdAt: '2026-06-01T00:00:00Z', attestationFormat: 'none', ...p,
});
const pageOf = (items: Credential[], total = items.length, page = 0) => ({ items, total, page, size: 50 });
const renderTab = () => render(<ToastHost><CredentialsTab tenant={tenant} /></ToastHost>);

describe('CredentialsTab V2', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.authEvents.mockResolvedValue([]);
  });

  it('검색어를 디바운스 후 서버 q 로 보낸다(앞뒤 공백 제거, 첫 페이지)', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1')]));
    renderTab();
    await screen.findByText('user-c1');
    expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, undefined);
    fireEvent.change(screen.getByRole('textbox', { name: 'Credential 검색' }), { target: { value: ' abc ' } });
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, 'abc'));
  });

  it('사용자 열은 긴 userHandle 을 말줄임(전체 값은 title) — 표가 카드 밖으로 밀리지 않게', async () => {
    const long = 'pld_4hnml33mtmxZTozXbiHNX8BVqiwmbkIx8yzPueI';
    api.list.mockResolvedValue(pageOf([cred('c1', { externalUserId: long })]));
    renderTab();
    const cell = await screen.findByText(long);
    expect(cell).toHaveClass('truncate');
    expect(cell).toHaveAttribute('title', long);
  });

  it('credential 이 하나도 없으면 빈 상태 일러스트', async () => {
    api.list.mockResolvedValue(pageOf([], 0));
    const { container } = renderTab();
    expect(await screen.findByText('Credential이 없습니다')).toBeInTheDocument();
    expect(container.querySelectorAll('img.empty__art')).toHaveLength(1);
  });

  it('검색 결과가 없으면 결과 없음 상태, 필터 지우기로 초기화', async () => {
    api.list.mockImplementation((_t: string, _p: number, _s: number, q?: string) => Promise.resolve(q ? pageOf([], 0) : pageOf([cred('c1')])));
    renderTab();
    await screen.findByText('user-c1');
    fireEvent.change(screen.getByRole('textbox', { name: 'Credential 검색' }), { target: { value: 'zzz' } });
    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '필터 지우기' }));
    expect(await screen.findByText('user-c1')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Credential 검색' })).toHaveValue('');
    expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, undefined);
  });

  it('+ 필터: 필터 행 토글, status 를 고르면 적용 칩이 생기고 현재 페이지 안에서 거른다', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1'), cred('c2', { status: 'REVOKED' })]));
    renderTab();
    await screen.findByText('user-c1');
    const toggle = screen.getByRole('button', { name: /필터 · aaguid · 상태/ });
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    const seg = within(screen.getByRole('group', { name: '상태 필터' }));
    expect(seg.getByRole('button', { name: '활성' })).toHaveAttribute('title', 'ACTIVE');
    expect(seg.getByRole('button', { name: '회수됨' })).toHaveAttribute('title', 'REVOKED');
    expect(seg.getByRole('button', { name: '전체' })).not.toHaveAttribute('title');
    fireEvent.click(seg.getByRole('button', { name: '회수됨' }));
    expect(screen.getByText('상태: 회수됨')).toBeInTheDocument();
    expect(screen.queryByText('user-c1')).toBeNull();
    expect(screen.getByText('user-c2')).toBeInTheDocument();
    expect(screen.getByText('이 페이지 2건 중 1건')).toBeInTheDocument();
    // 표의 상태 배지는 한국어 라벨 + 원문 title
    expect(screen.getAllByText('회수').some((el) => el.closest('.badge')?.getAttribute('title') === 'REVOKED')).toBe(true);
    expect(screen.getByRole('button', { name: 'CSV 내보내기 (현재 페이지 1건)' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '상태 필터 제거' }));
    expect(screen.getByText('user-c1')).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(1); // 클라이언트 필터는 서버를 다시 부르지 않는다
  });

  it('상세 이전/다음: 페이지 안에서는 바로, 경계에서는 다음/이전 페이지를 불러와 이어간다', async () => {
    const p0 = [cred('c1'), cred('c2')];
    const p1 = [cred('c3')];
    api.list.mockImplementation((_t: string, page: number) => Promise.resolve(page === 0 ? pageOf(p0, 51, 0) : pageOf(p1, 51, 1)));
    renderTab();
    fireEvent.click(await screen.findByText('user-c1'));
    const dlg = screen.getByRole('dialog', { name: /Credential 상세/ });
    expect(within(dlg).getByText('1 / 51')).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '이전 credential' })).toBeDisabled();

    fireEvent.click(within(dlg).getByRole('button', { name: '다음 credential' }));
    expect(within(dlg).getByText('2 / 51')).toBeInTheDocument();
    expect(within(dlg).getByText('user-c2')).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(1);

    fireEvent.click(within(dlg).getByRole('button', { name: '다음 credential' }));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('t1', 1, 50, undefined));
    expect(await within(dlg).findByText('51 / 51')).toBeInTheDocument();
    expect(within(dlg).getByText('user-c3')).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '다음 credential' })).toBeDisabled();

    fireEvent.click(within(dlg).getByRole('button', { name: '이전 credential' }));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('t1', 0, 50, undefined));
    expect(await within(dlg).findByText('2 / 51')).toBeInTheDocument();
    expect(within(dlg).getByText('user-c2')).toBeInTheDocument();
  });

  it('회수: L3 — 마지막 8자를 정확히 입력해야 "회수", 상세 푸터에서도 진입', async () => {
    api.list.mockResolvedValue(pageOf([cred('cred-0000-abcdefgh')]));
    api.revoke.mockResolvedValue(undefined);
    renderTab();
    fireEvent.click(await screen.findByText('user-cred-0000-abcdefgh'));
    fireEvent.click(screen.getByRole('button', { name: 'Credential 회수…' }));
    expect(screen.queryByRole('dialog', { name: /Credential 상세/ })).toBeNull();

    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    expect(within(dlg).getByText(/더 이상 로그인할 수 없습니다/)).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    const confirm = within(dlg).getByRole('button', { name: '회수' });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: 'abcdefgh' } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(api.revoke).toHaveBeenCalledWith('t1', 'cred-0000-abcdefgh'));
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(await screen.findByText('Credential을 회수했습니다.')).toBeInTheDocument();
  });

  it('회수 처리 중에는 "회수 중…" 비활성, 연타해도 한 번만', async () => {
    api.list.mockResolvedValue(pageOf([cred('aaaa-11111111')]));
    let finish!: () => void;
    api.revoke.mockReturnValue(new Promise<void>((r) => { finish = r; }));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '회수 · …11111111' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: '11111111' } });
    const btn = within(dlg).getByRole('button', { name: '회수' });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(api.revoke).toHaveBeenCalledTimes(1);
    expect(btn).toBeDisabled();
    expect(btn).toHaveTextContent('회수 중…');
    finish();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });

  it('회수 실패는 다이얼로그 안 인라인 오류, 닫히지 않고 토스트 없음', async () => {
    api.list.mockResolvedValue(pageOf([cred('aaaa-11111111')]));
    api.revoke.mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '회수 · …11111111' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: '11111111' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '회수' }));
    expect(await within(dlg).findByText('회수하지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('회수 실패')).toBeNull();
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it('회수 성공 직후 다이얼로그는 닫히고, 목록 새로고침이 끝나길 기다리지 않는다', async () => {
    let finishRefresh!: (v: ReturnType<typeof pageOf>) => void;
    api.list.mockResolvedValueOnce(pageOf([cred('aaaa-11111111')])).mockReturnValueOnce(new Promise((r) => { finishRefresh = r; }));
    api.revoke.mockResolvedValue(undefined);
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '회수 · …11111111' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: '11111111' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '회수' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('Credential을 회수했습니다.')).toBeInTheDocument();
    await act(async () => { finishRefresh(pageOf([])); });
  });

  it('회수 성공 뒤 새로고침이 실패하면 기존 목록은 남기고 경고 + 다시 시도', async () => {
    api.list.mockResolvedValueOnce(pageOf([cred('aaaa-11111111'), cred('bbbb-22222222')]))
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce(pageOf([cred('bbbb-22222222')]));
    api.revoke.mockResolvedValue(undefined);
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '회수 · …11111111' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Credential을 회수할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /마지막 8자/ }), { target: { value: '11111111' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '회수' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(await screen.findByText('Credential 목록을 새로 고치지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('user-aaaa-11111111')).toBeInTheDocument();
    expect(screen.getByText('2건')).toBeInTheDocument();
    expect(screen.queryByText('Credential 목록을 불러오지 못했습니다')).toBeNull();
    expect(screen.getByRole('textbox', { name: 'Credential 검색' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(screen.getByRole('button', { name: '다시 시도 중…' })).toBeDisabled();
    await waitFor(() => expect(screen.queryByText('user-aaaa-11111111')).toBeNull());
    expect(screen.queryByText('Credential 목록을 새로 고치지 못했습니다')).toBeNull();
  });

  it('다른 페이지 요청이 실패하면 이전 페이지 행을 남기지 않고 전체 오류 + 다시 시도', async () => {
    api.list.mockImplementation((_t: string, page: number) => (page === 0
      ? Promise.resolve(pageOf([cred('c1')], 120, 0))
      : Promise.reject(new ApiError(503, 'C999', 'HTTP 503'))));
    renderTab();
    await screen.findByText('user-c1');
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Credential 목록을 불러오지 못했습니다');
    expect(screen.queryByText('user-c1')).toBeNull();
    expect(screen.queryByText('Credential 목록을 새로 고치지 못했습니다')).toBeNull();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('표의 휴지통 버튼은 ACTIVE 행에만', async () => {
    api.list.mockResolvedValue(pageOf([cred('aaaa-11111111'), cred('bbbb-22222222', { status: 'REVOKED' })]));
    renderTab();
    await screen.findByText('user-aaaa-11111111');
    expect(screen.getByRole('button', { name: '회수 · …11111111' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '회수 · …22222222' })).toBeNull();
  });
  it('검색 placeholder 는 실제 검색 범위(Credential ID · 사용자 ID), 별칭은 안 된다고 알린다', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1')]));
    renderTab();
    await screen.findByText('user-c1');
    const box = screen.getByRole('textbox', { name: 'Credential 검색' });
    expect(box).toHaveAttribute('placeholder', 'Credential ID · 사용자 ID 검색');
    expect(box).toHaveAttribute('title', expect.stringContaining('별칭'));
  });

  it('필터가 없으면 서버 총 건수, CSV 라벨에 현재 페이지 건수', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1'), cred('c2')], 120));
    renderTab();
    await screen.findByText('user-c1');
    expect(screen.getByText('120건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV 내보내기 (현재 페이지 2건)' })).toBeEnabled();
  });

  it('로드 실패: LoadError, 건수 "—", CSV·검색 비활성, 페이지네이션 없음, 다시 시도로 복구', async () => {
    api.list.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503')).mockResolvedValueOnce(pageOf([cred('c1')]));
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Credential 목록을 불러오지 못했습니다');
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
    expect(screen.queryByText('Credential이 없습니다')).toBeNull();
    expect(screen.queryByText('0건')).toBeNull();
    expect(screen.queryByRole('button', { name: '다음 페이지' })).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /CSV 내보내기/ })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Credential 검색' })).toBeDisabled();
    expect(screen.queryByText('credentials 로드 실패')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('user-c1')).toBeInTheDocument();
  });

  it('로딩 중에는 Skeleton', async () => {
    let resolve!: (v: ReturnType<typeof pageOf>) => void;
    api.list.mockReturnValue(new Promise((r) => { resolve = r; }));
    renderTab();
    expect(screen.getByRole('status', { name: 'Credential 목록 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    resolve(pageOf([cred('c1')]));
    await screen.findByText('user-c1');
  });

  it('전송 방식은 한국어 라벨, 원문은 title', async () => {
    api.list.mockResolvedValue(pageOf([cred('c1', { transports: ['internal', 'usb'] })]));
    renderTab();
    await screen.findByText('user-c1');
    expect(screen.getByText('내장')).toHaveAttribute('title', 'internal');
    expect(screen.getByText('USB')).toHaveAttribute('title', 'usb');
  });
});
