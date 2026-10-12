// admin-ui/src/pages/LoginPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';

const links = vi.hoisted(() => ({ apiDocs: '', rpGuide: '', support: '' }));
vi.mock('@/config/links', () => ({ HELP_LINKS: links }));
vi.mock('@/api/client', () => ({
  api: { get: vi.fn(), loginForm: vi.fn() },
  getMe: vi.fn(),
}));

import { api, getMe } from '@/api/client';
import { ToastHost } from '@/ui';
import LoginPage from './LoginPage';

const me: Me = { email: 'alice@crosscert.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

function renderLogin(onLogin = vi.fn()) {
  const utils = render(<ToastHost><LoginPage onLogin={onLogin} /></ToastHost>);
  return { onLogin, ...utils };
}
function fillAndSubmit(email: string, pw: string) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: pw } });
  fireEvent.click(screen.getByRole('button', { name: '로그인' }));
}

beforeEach(() => {
  vi.mocked(api.get).mockReset().mockRejectedValue(new Error('prod'));
  vi.mocked(api.loginForm).mockReset();
  vi.mocked(getMe).mockReset();
});
afterEach(() => { links.support = ''; });

describe('LoginPage V3', () => {
  it('브랜드 패널: 워드마크·헤드라인·장식 일러스트, 가짜 지표 대신 콘솔이 하는 일', () => {
    const { container } = renderLogin();
    expect(screen.getByAltText('CROSSCERT')).toHaveAttribute('src', '/admin/crosscert-logo-transparent.png');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('패스키 인증을운영하는 콘솔.');
    expect(container.querySelector('aside img[alt=""]')).not.toBeNull();
    expect(screen.getByText('이 콘솔에서 하는 일')).toBeInTheDocument();
    for (const d of ['tenant 생성 · 정책 설정 · API 키 발급', '사용자 패스키 조회와 회수', '감사 로그 무결성 확인과 내보내기']) expect(screen.getByText(d)).toBeInTheDocument();
    for (const fake of ['58', '2.4M', '100%', '활성 tenant', 'ceremony / 24h', 'chain 무결성']) expect(screen.queryByText(fake)).toBeNull();
    expect(container.textContent).not.toMatch(/audit log|hash chain|ceremony/);
  });

  it('이메일 칸에 자동 포커스', () => {
    renderLogin();
    expect(document.activeElement).toBe(screen.getByLabelText('이메일'));
  });

  it('비밀번호 라벨 오른쪽에 재설정 링크, 하단에 가입 요청 링크와 자동 로그아웃 안내', () => {
    renderLogin();
    expect(screen.getByRole('link', { name: '비밀번호를 잊으셨나요?' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(screen.getByRole('link', { name: '가입 요청' })).toHaveAttribute('href', '/admin/signup');
    expect(screen.getByText('30분 동안 활동이 없으면 자동으로 로그아웃됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toHaveClass('btn', 'btn--primary', 'btn--lg');
  });

  it('로그인 실패: 폼 맨 위 경고 하나만(토스트 없음), 이메일 유지 · 비밀번호만 비우고 포커스', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 401 });
    renderLogin();
    fillAndSubmit('a@x.com', 'wrong');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인 실패');
    expect(alert).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다.');
    expect(alert).toHaveTextContent('A001');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(document.querySelector('.toast')).toBeNull();
    expect(screen.getByLabelText('이메일')).toHaveValue('a@x.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('');
    expect(document.activeElement).toBe(screen.getByLabelText('비밀번호'));
    // 이메일 입력보다 앞(폼 상단)에 위치
    expect(alert.compareDocumentPosition(screen.getByLabelText('이메일')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('입력을 바꾸면 경고가 사라진다', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 401 });
    renderLogin();
    fillAndSubmit('a@x.com', 'wrong');
    await screen.findByRole('alert');
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'n' } });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('5xx 응답은 일시 오류 + 다시 시도, 입력 유지', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: false, status: 503 });
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('지금은 로그인할 수 없습니다');
    expect(alert).toHaveTextContent('서버가 응답하지 않습니다. 입력한 내용은 그대로 있습니다.');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(alert).not.toHaveTextContent('이메일 또는 비밀번호');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('pw');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(api.loginForm).toHaveBeenCalledTimes(2));
    expect(api.loginForm).toHaveBeenLastCalledWith('a@x.com', 'pw');
  });

  it('네트워크 오류(fetch TypeError)도 일시 오류', async () => {
    vi.mocked(api.loginForm).mockRejectedValue(new TypeError('Failed to fetch'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    expect(await screen.findByRole('alert')).toHaveTextContent('지금은 로그인할 수 없습니다');
  });

  it('서버 예외(ApiError 503)도 일시 오류로 표시', async () => {
    vi.mocked(api.loginForm).mockRejectedValue(new ApiError(503, 'C503', '서버 점검 중'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('지금은 로그인할 수 없습니다');
    expect(alert).toHaveTextContent('HTTP 503');
  });

  it('로그인 뒤 /me 가 403 이면 쉬운 문구로', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: true, status: 200 });
    vi.mocked(getMe).mockRejectedValue(new ApiError(403, 'A003', 'Forbidden'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인 실패');
    expect(alert).toHaveTextContent('권한이 없습니다');
  });

  it('성공하면 getMe 결과로 onLogin', async () => {
    vi.mocked(api.loginForm).mockResolvedValue({ ok: true, status: 200 });
    vi.mocked(getMe).mockResolvedValue(me);
    const { onLogin } = renderLogin();
    fillAndSubmit('alice@crosscert.com', 'pw');
    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(me));
    expect(api.loginForm).toHaveBeenCalledWith('alice@crosscert.com', 'pw');
  });

  it('비밀번호 표시 토글', () => {
    renderLogin();
    const pw = screen.getByLabelText('비밀번호');
    expect(pw).toHaveAttribute('type', 'password');
    const toggle = screen.getByRole('button', { name: '비밀번호 표시' });
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(toggle);
    expect(pw).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: '비밀번호 숨기기' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('문의 주소가 있을 때만 하단 "도움말 · 운영자에게 문의" 링크', () => {
    const { unmount } = renderLogin();
    expect(screen.queryByRole('link', { name: '도움말 · 운영자에게 문의' })).toBeNull();
    unmount();
    links.support = 'mailto:ops@example.com';
    renderLogin();
    expect(screen.getByRole('link', { name: '도움말 · 운영자에게 문의' })).toHaveAttribute('href', 'mailto:ops@example.com');
  });

  it('dev/local 이 아니면 DEV 데모 카드가 없다', async () => {
    renderLogin();
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/admin/api/profile'));
    expect(screen.queryByText('데모 계정으로 로그인')).toBeNull();
  });

  it('dev 프로필이면 DEV 데모 카드가 보이고 클릭 시 alice 계정을 채운다', async () => {
    vi.mocked(api.get).mockResolvedValue({ active: ['dev'] });
    renderLogin();
    const card = await screen.findByRole('button', { name: /데모 계정으로 로그인/ });
    expect(card).toHaveTextContent('DEV');
    expect(card).toHaveTextContent('Platform Operator / alice@crosscert.com');
    fireEvent.click(card);
    expect(card).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('이메일')).toHaveValue('alice@crosscert.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('alice-temp-pw');
  });
});
