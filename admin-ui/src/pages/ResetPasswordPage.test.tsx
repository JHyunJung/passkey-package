import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ApiError } from '@/api/types';

vi.mock('@/api/passwordReset', () => ({ passwordResetApi: { request: vi.fn(), confirm: vi.fn() } }));

import { passwordResetApi } from '@/api/passwordReset';
import ResetPasswordPage from './ResetPasswordPage';

const withToken = () => renderWithDataRouter(<ResetPasswordPage />, { initialPath: '/reset-password?token=t1' });
function fill(pw: string, pw2: string) {
  fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: pw } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: pw2 } });
}

beforeEach(() => { vi.mocked(passwordResetApi.confirm).mockReset().mockResolvedValue({ reset: true }); });

describe('ResetPasswordPage V2', () => {
  it('token 이 없으면 무효 링크 상태: 경고 + 재설정 다시 요청', () => {
    const { container } = renderWithDataRouter(<ResetPasswordPage />, { initialPath: '/reset-password' });
    expect(screen.getByRole('status')).toHaveTextContent('링크를 사용할 수 없습니다');
    expect(screen.getByText('유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '재설정 다시 요청' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(screen.getByRole('link', { name: '← 로그인으로' })).toHaveAttribute('href', '/admin');
    expect(container.querySelector('input')).toBeNull();
  });

  it('12자 미만이면 제출 불가, 12자 이상이면 미터가 충족 표시', () => {
    withToken();
    fill('short', 'short');
    expect(screen.getByText('12자 이상')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeDisabled();
    fill('password-12chars', 'password-12chars');
    expect(screen.getByText('12자 이상 충족')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).not.toBeDisabled();
  });

  it('불일치면 오류 표시 + 제출 불가', () => {
    withToken();
    fill('password-12chars', 'password-12charX');
    expect(screen.getByText('비밀번호가 일치하지 않습니다.')).toHaveClass('field__error');
    expect(screen.getByLabelText('새 비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeDisabled();
  });

  it('성공하면 같은 카드에서 변경 완료 상태', async () => {
    const { container } = withToken();
    const card = container.querySelector('.auth-card');
    fill('password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
    expect(passwordResetApi.confirm).toHaveBeenCalledWith('t1', 'password-12chars');
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('link', { name: '로그인 →' })).toHaveAttribute('href', '/admin');
  });

  it('서버 400 은 serverMessage 를 오류 배너로', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValue(new ApiError(400, 'C400', 'token expired'));
    withToken();
    fill('password-12chars', 'password-12chars');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('token expired');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeInTheDocument();
  });
});
