import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

vi.mock('@/api/passwordReset', () => ({ passwordResetApi: { request: vi.fn(), confirm: vi.fn() } }));

import { passwordResetApi } from '@/api/passwordReset';
import ForgotPasswordPage from './ForgotPasswordPage';

afterEach(cleanup);
beforeEach(() => { vi.mocked(passwordResetApi.request).mockReset().mockResolvedValue({ ok: true }); });

async function submit(email: string) {
  const utils = render(<ForgotPasswordPage />);
  const card = utils.container.querySelector('.auth-card');
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: '재설정 링크 보내기' }));
  await screen.findByText('메일을 확인하세요');
  return { ...utils, card };
}

describe('ForgotPasswordPage V2', () => {
  it('공통 셸: 워드마크 + 카드 + 카드 밖 로그인 링크', () => {
    const { container } = render(<ForgotPasswordPage />);
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    const back = screen.getByRole('link', { name: '← 로그인으로' });
    expect(back).toHaveAttribute('href', '/admin');
    expect(back.closest('.auth-card')).toBeNull();
  });

  it('빈 이메일이면 제출 불가', () => {
    render(<ForgotPasswordPage />);
    expect(screen.getByRole('button', { name: '재설정 링크 보내기' })).toBeDisabled();
  });

  it('제출 후 같은 카드 안에서 완료 상태로 바뀐다', async () => {
    const { container, card } = await submit('  a@x.com ');
    expect(passwordResetApi.request).toHaveBeenCalledWith('a@x.com');
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('heading', { name: '비밀번호 재설정' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.');
    expect(screen.getByText('메일이 오지 않으면 스팸함을 확인하거나 플랫폼 운영자에게 문의하세요.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('button', { name: '재설정 링크 보내기' })).toBeNull();
  });

  it('요청이 실패해도 완료 카드 내용이 성공 때와 같다(계정 존재 비노출)', async () => {
    const ok = await submit('a@x.com');
    const okText = ok.container.querySelector('.auth-card')!.textContent;
    cleanup();
    vi.mocked(passwordResetApi.request).mockRejectedValueOnce(new Error('404'));
    const fail = await submit('nobody@x.com');
    const failText = fail.container.querySelector('.auth-card')!.textContent;
    expect(failText).toBe(okText);
    expect(failText).not.toContain('nobody@x.com');
  });
});
