import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { ApiError } from '@/api/types';

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
    // V2 아트보드: 가입 요청만 420px 열(나머지 인증 화면은 400px)
    expect(container.querySelector('.auth__col')).toHaveClass('auth__col--wide');
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
    expect(screen.getByText('5자 입력됨 · 12–128자로 입력하세요')).toHaveClass('field__error');
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

  it('비밀번호는 최대 128자, 허용 범위 "12–128자"를 미리 보여 준다', () => {
    render(<SignupRequestPage />);
    expect(screen.getByLabelText('비밀번호')).toHaveAttribute('maxLength', '128');
    expect(screen.getByText('12–128자')).toHaveClass('hint');
  });

  it('이메일 형식 오류는 칸을 벗어날 때 인라인으로', () => {
    render(<SignupRequestPage />);
    const email = screen.getByLabelText('이메일');
    fireEvent.change(email, { target: { value: 'not-an-email' } });
    expect(screen.queryByText(/이메일 형식이 올바르지 않습니다/)).toBeNull();
    fireEvent.blur(email);
    expect(screen.getByText('이메일 형식이 올바르지 않습니다. 예: you@company.com')).toHaveClass('field__error');
    expect(email).toHaveAttribute('aria-invalid', 'true');
    expect(email).toHaveAttribute('aria-describedby', 'signup-email-error');
    fireEvent.change(email, { target: { value: 'a@x.com' } });
    expect(screen.queryByText(/이메일 형식이 올바르지 않습니다/)).toBeNull();
  });

  it('4xx 는 성공과 같은 접수 안내(계정 존재 비노출)', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(409, 'C409', 'duplicate'));
    render(<SignupRequestPage />);
    fill('dup@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    expect(await screen.findByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument();
  });

  it('5xx 면 보내지 못했다고 알리고 입력을 유지, "다시 시도"에 포커스, 다시 시도가 성공하면 접수', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    render(<SignupRequestPage />);
    fill('d@x.com', 'password-12chars', 'password-12chars');
    fireEvent.change(screen.getByLabelText(/요청 사유/), { target: { value: '보안팀' } });
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('요청을 보내지 못했습니다.');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(screen.queryByRole('heading', { name: '요청이 접수되었습니다' })).toBeNull();
    expect(screen.getByLabelText('이메일')).toHaveValue('d@x.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('password-12chars');
    expect(screen.getByLabelText(/요청 사유/)).toHaveValue('보안팀');
    const retry = screen.getByRole('button', { name: '다시 시도' });
    await waitFor(() => expect(document.activeElement).toBe(retry));
    fireEvent.click(retry);
    expect(await screen.findByRole('heading', { name: '요청이 접수되었습니다' })).toBeInTheDocument();
  });

  it('네트워크 오류(fetch TypeError)도 보내지 못했다고 알린다', async () => {
    (signupRequestsApi.request as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<SignupRequestPage />);
    fill('e@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 보내지 못했습니다.');
  });

  it('요청 중에는 입력이 읽기 전용이고 중복 제출되지 않아, 늦은 실패가 입력을 덮지 않는다', async () => {
    const request = signupRequestsApi.request as ReturnType<typeof vi.fn>;
    request.mockClear();
    let rejectFirst!: (e: unknown) => void;
    request.mockReturnValueOnce(new Promise((_, reject) => { rejectFirst = reject; }));
    render(<SignupRequestPage />);
    fill('f@x.com', 'password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '가입 요청 보내기' }));
    const pending = await screen.findByRole('button', { name: '전송 중…' });
    expect(pending).toBeDisabled();
    for (const label of ['이메일', '비밀번호', '비밀번호 확인', /요청 사유/]) {
      expect(screen.getByLabelText(label)).toHaveAttribute('readonly');
    }
    fireEvent.submit(pending.closest('form')!);
    expect(request).toHaveBeenCalledTimes(1);
    rejectFirst(new ApiError(503, 'C999', 'HTTP 503'));
    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 보내지 못했습니다.');
    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('readonly');
    expect(screen.getByLabelText('이메일')).toHaveValue('f@x.com');
    expect(screen.getByLabelText('비밀번호')).toHaveValue('password-12chars');
  });
});
