import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const get = vi.fn();
const post = vi.fn();
vi.mock('@/api/client', () => ({
  api: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a) },
  getMe: vi.fn(),
}));

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
});
