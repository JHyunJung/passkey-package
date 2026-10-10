// admin-ui/src/pages/LoginPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';

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

describe('LoginPage V2', () => {
  it('브랜드 패널: 워드마크·헤드라인·장식 일러스트·지표 3개', () => {
    const { container } = renderLogin();
    expect(screen.getByAltText('CROSSCERT')).toHaveAttribute('src', '/admin/crosscert-logo-transparent.png');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('패스키 인증을운영하는 콘솔.');
    expect(container.querySelector('aside img[alt=""]')).not.toBeNull();
    for (const l of ['활성 tenant', 'ceremony / 24h', 'chain 무결성']) expect(screen.getByText(l)).toBeInTheDocument();
  });

  it('비밀번호 라벨 오른쪽에 재설정 링크, 하단에 가입 요청 링크와 자동 로그아웃 안내', () => {
    renderLogin();
    expect(screen.getByRole('link', { name: '비밀번호를 잊으셨나요?' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(screen.getByRole('link', { name: '가입 요청' })).toHaveAttribute('href', '/admin/signup');
    expect(screen.getByText('30분 동안 활동이 없으면 자동으로 로그아웃됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인' })).toHaveClass('btn', 'btn--primary', 'btn--lg');
  });

  it('로그인 실패: 폼 맨 위 alert 에 메시지와 코드', async () => {
    vi.mocked(api.loginForm).mockResolvedValue(false);
    renderLogin();
    fillAndSubmit('a@x.com', 'wrong');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인 실패');
    expect(alert).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다.');
    expect(alert).toHaveTextContent('A001');
    // 이메일 입력보다 앞(폼 상단)에 위치
    expect(alert.compareDocumentPosition(screen.getByLabelText('이메일')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('서버 예외는 code · serverMessage 로 표시', async () => {
    vi.mocked(api.loginForm).mockRejectedValue(new ApiError(503, 'C503', '서버 점검 중'));
    renderLogin();
    fillAndSubmit('a@x.com', 'pw');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버 점검 중');
    expect(alert).toHaveTextContent('C503');
  });

  it('성공하면 getMe 결과로 onLogin', async () => {
    vi.mocked(api.loginForm).mockResolvedValue(true);
    vi.mocked(getMe).mockResolvedValue(me);
    const { onLogin } = renderLogin();
    fillAndSubmit('alice@crosscert.com', 'pw');
    await waitFor(() => expect(onLogin).toHaveBeenCalledWith(me));
    expect(api.loginForm).toHaveBeenCalledWith('alice@crosscert.com', 'pw');
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
