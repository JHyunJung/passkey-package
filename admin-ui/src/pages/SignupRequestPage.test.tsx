import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

vi.mock('@/api/signupRequests', () => ({
  signupRequestsApi: { request: vi.fn().mockResolvedValue({ accepted: true }) },
}));

import { signupRequestsApi } from '@/api/signupRequests';
import SignupRequestPage from './SignupRequestPage';

afterEach(cleanup);

function fill(email: string, pw: string, pw2: string) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: pw } });
  fireEvent.change(screen.getByLabelText('비밀번호 확인'), { target: { value: pw2 } });
}

describe('SignupRequestPage V2', () => {
  it('공통 셸: 워드마크 + 카드 + 카드 밖 로그인 링크', () => {
    const { container } = render(<SignupRequestPage />);
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    expect(screen.getByRole('heading', { name: '관리자 계정 가입 요청' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '← 로그인으로' }).closest('.auth-card')).toBeNull();
  });

  it('keeps submit disabled while passwords differ', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'password-12chars', 'password-12charX');
    expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toHaveClass('field__error');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('keeps submit disabled for a password shorter than 12', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'short', 'short');
    expect(screen.getByText('12자 이상')).toHaveClass('field__error');
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('keeps submit disabled for an invalid email', () => {
    render(<SignupRequestPage />);
    fill('not-an-email', 'password-12chars', 'password-12chars');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('제출 후 같은 카드 안에서 접수 완료 상태(일러스트·제목·돌아가기)', async () => {
    const { container } = render(<SignupRequestPage />);
    const card = container.querySelector('.auth-card');
    fill(' A@X.com ', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    expect(await screen.findByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument();
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(card!.querySelector('img[alt=""]')).not.toBeNull();
    expect(screen.getByText('운영자 승인 후 이메일로 안내됩니다. 승인 전에는 로그인할 수 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '로그인으로 돌아가기' })).toHaveAttribute('href', '/admin');
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(signupRequestsApi.request).toHaveBeenCalledWith({
      email: 'a@x.com', password: 'password-12chars', reason: undefined,
    });
  });

  it('shows the same accepted notice even when the request fails', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('boom'));
    render(<SignupRequestPage />);
    fill('b@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument(),
    );
  });

  it('사유는 trim 해서 보낸다', async () => {
    render(<SignupRequestPage />);
    fill('c@x.com', 'password-12chars', 'password-12chars');
    fireEvent.change(screen.getByLabelText(/요청 사유/), { target: { value: '  보안팀 · RP 운영  ' } });
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await screen.findByRole('heading', { name: '요청이 접수되었습니다' });
    expect(signupRequestsApi.request).toHaveBeenLastCalledWith({
      email: 'c@x.com', password: 'password-12chars', reason: '보안팀 · RP 운영',
    });
  });
});
