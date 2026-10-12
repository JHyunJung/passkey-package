import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const get = vi.fn();
const post = vi.fn();
vi.mock('@/api/client', () => ({
  api: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a) },
  getMe: vi.fn(),
}));

vi.mock('@/api/mfa', () => ({ mfaApi: { verify: vi.fn() } }));

import { mfaApi } from '@/api/mfa';
import App from './App';

let release: (() => void) | null = null;
afterEach(async () => { await act(async () => { release?.(); }); release = null; get.mockReset(); post.mockReset(); });

describe('App 로그아웃', () => {
  it('서버 로그아웃 요청이 끝나지 않아도 화면은 즉시 로그인으로 돌아간다', async () => {
    get.mockResolvedValue({ email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: true, mfaRequired: true, sessionIdleTimeoutMinutes: 30 });
    // 응답하지 않는 로그아웃 — 테스트가 끝나면 풀어 준다.
    post.mockReturnValue(new Promise<void>((resolve) => { release = () => resolve(); }));
    render(<MemoryRouter><App /></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button', { name: '← 로그인으로' }));
    expect(post).toHaveBeenCalledWith('/admin/logout', {});
    expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument();
  });

  it('MFA 확인 뒤 /me 응답이 늦게 와도 그 사이 "로그인으로" 를 눌렀다면 콘솔을 다시 열지 않는다', async () => {
    const mfaMe = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: true, mfaRequired: true, sessionIdleTimeoutMinutes: 30 };
    let resolveMe!: (v: unknown) => void;
    get.mockRejectedValue(new Error('콘솔 화면의 다른 조회')); // 콘솔이 다시 열리면 부를 조회들
    get.mockResolvedValueOnce(mfaMe).mockReturnValueOnce(new Promise((r) => { resolveMe = r; }));
    post.mockResolvedValue(undefined);
    vi.mocked(mfaApi.verify).mockResolvedValue({ verified: true });
    render(<MemoryRouter><App /></MemoryRouter>);
    const first = await screen.findByRole('textbox', { name: /1번째/ });
    // 6자리를 붙여 넣으면 자동으로 확인한다.
    await act(async () => { fireEvent.paste(first, { clipboardData: { getData: () => '123456' } }); });
    expect(mfaApi.verify).toHaveBeenCalledWith('123456');
    expect(get).toHaveBeenCalledTimes(2); // reloadMe 가 /me 를 기다리는 중
    fireEvent.click(screen.getByRole('button', { name: '← 로그인으로' }));
    expect(await screen.findByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument();
    await act(async () => { resolveMe({ ...mfaMe, mfaRequired: false }); });
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
    expect(screen.getByRole('heading', { name: '관리자 로그인' })).toBeInTheDocument();
  });
});
