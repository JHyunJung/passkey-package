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

describe('SignupRequestPage', () => {
  it('keeps submit disabled while passwords differ', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'password-12chars', 'password-12charX');
    expect(screen.getByText('두 비밀번호가 일치하지 않습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('keeps submit disabled for a password shorter than 12', () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'short', 'short');
    expect(screen.getByRole('button', { name: '가입 요청 보내기' })).toBeDisabled();
  });

  it('shows the accepted notice after submit and never reveals account existence', async () => {
    render(<SignupRequestPage />);
    fill('a@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    await waitFor(() =>
      expect(screen.getByText(/요청이 접수되었습니다/)).toBeInTheDocument(),
    );
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
      expect(screen.getByText(/요청이 접수되었습니다/)).toBeInTheDocument(),
    );
  });
});
