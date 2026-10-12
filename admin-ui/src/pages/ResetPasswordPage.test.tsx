import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
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
function submitValid() {
  fill('password-12chars', 'password-12chars');
  fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
}
const backLink = () => screen.getByRole('link', { name: '← 로그인으로' });

beforeEach(() => { vi.mocked(passwordResetApi.confirm).mockReset().mockResolvedValue({ reset: true }); });

describe('ResetPasswordPage V3', () => {
  it('token 이 없으면 무효 링크 상태: 경고 + 재설정 링크 다시 받기 + 로그인으로', () => {
    const { container } = renderWithDataRouter(<ResetPasswordPage />, { initialPath: '/reset-password' });
    expect(screen.getByRole('status')).toHaveTextContent('링크를 사용할 수 없습니다');
    expect(screen.getByText('유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '재설정 링크 다시 받기' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(backLink()).toHaveAttribute('href', '/admin');
    expect(container.querySelector('input')).toBeNull();
  });

  it('입력 상태에도 카드 밖 "← 로그인으로"', () => {
    const { container } = withToken();
    expect(backLink()).toHaveAttribute('href', '/admin');
    expect(backLink().closest('.auth-card')).toBeNull();
    expect(container.querySelector('input')).not.toBeNull();
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

  it('성공하면 같은 카드에서 변경 완료 상태, 로그인으로 링크도 유지', async () => {
    const { container } = withToken();
    const card = container.querySelector('.auth-card');
    submitValid();
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
    expect(passwordResetApi.confirm).toHaveBeenCalledWith('t1', 'password-12chars');
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('link', { name: '로그인 →' })).toHaveAttribute('href', '/admin');
    expect(backLink()).toHaveAttribute('href', '/admin');
  });

  it('토큰 관련 400 은 만료 카드 + 재설정 링크 다시 받기, 입력 폼은 사라진다', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValue(new ApiError(400, 'C400', 'token expired'));
    const { container } = withToken();
    submitValid();
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('링크가 만료되었거나 이미 사용되었습니다');
    expect(screen.getByRole('link', { name: '재설정 링크 다시 받기' })).toHaveAttribute('href', '/admin/forgot-password');
    expect(container.querySelector('input')).toBeNull();
    expect(backLink()).toBeInTheDocument();
  });

  it('토큰과 무관한 400 은 서버 메시지를 오류 배너로, 폼 유지', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValue(new ApiError(400, 'C400', 'password too weak'));
    withToken();
    submitValid();
    expect(await screen.findByRole('alert')).toHaveTextContent('password too weak');
    expect(screen.getByRole('button', { name: '비밀번호 변경' })).toBeInTheDocument();
  });

  it('5xx 는 "비밀번호를 바꾸지 못했습니다" + 다시 시도, 입력 유지', async () => {
    vi.mocked(passwordResetApi.confirm).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    withToken();
    submitValid();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('비밀번호를 바꾸지 못했습니다.');
    expect(alert).toHaveTextContent('입력한 내용은 그대로 있습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    expect(screen.getByLabelText('새 비밀번호')).toHaveValue('password-12chars');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(passwordResetApi.confirm).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
  });

  it('요청 중에는 입력이 읽기 전용이고 제출이 한 번만 나간다', async () => {
    let resolve!: (v: { reset: boolean }) => void;
    vi.mocked(passwordResetApi.confirm).mockReturnValueOnce(new Promise((res) => { resolve = res; }));
    withToken();
    submitValid();
    expect(screen.getByLabelText('새 비밀번호')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('새 비밀번호 확인')).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: '변경 중…' })).toBeDisabled();
    fireEvent.submit(screen.getByLabelText('새 비밀번호').closest('form')!);
    expect(passwordResetApi.confirm).toHaveBeenCalledTimes(1);
    resolve({ reset: true });
    expect(await screen.findByText('변경 완료')).toBeInTheDocument();
  });
});
