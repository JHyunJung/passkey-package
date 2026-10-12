// admin-ui/src/pages/MfaChallenge.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ApiError } from '@/api/types';

vi.mock('@/api/mfa', () => ({ mfaApi: { verify: vi.fn() } }));

import { mfaApi } from '@/api/mfa';
import { ToastHost } from '@/ui';
import MfaChallenge from './MfaChallenge';

function renderChallenge(onVerified = vi.fn(), onLogout = vi.fn()) {
  const utils = render(
    <ToastHost>
      <MfaChallenge onVerified={onVerified} onLogout={onLogout} />
    </ToastHost>,
  );
  return { onVerified, onLogout, ...utils };
}
const cell = (i: number) => screen.getByRole('textbox', { name: `인증 코드 ${i + 1}번째 자리` }) as HTMLInputElement;
const cellValues = () => [0, 1, 2, 3, 4, 5].map((i) => cell(i).value).join('');
const toRecovery = () => fireEvent.click(screen.getByRole('button', { name: /복구 코드로 로그인/ }));
const wrongCode = () => new ApiError(401, 'invalid_code', 'invalid_code');

beforeEach(() => {
  vi.mocked(mfaApi.verify).mockReset().mockResolvedValue({ verified: true });
});

describe('MfaChallenge V3', () => {
  it('공통 카드 셸: 워드마크·장식 일러스트·OTP 6칸·카드 밖 "← 로그인으로"', () => {
    const { container, onLogout } = renderChallenge();
    expect(container.querySelector('.auth .auth-card')).not.toBeNull();
    expect(screen.getByAltText('CROSSCERT')).toHaveClass('auth__logo');
    expect(container.querySelector('.auth-card img[alt=""]')).not.toBeNull();
    expect(screen.getByRole('heading', { name: '2단계 인증' })).toBeInTheDocument();
    expect(screen.getAllByRole('textbox')).toHaveLength(6);
    const back = screen.getByRole('button', { name: '← 로그인으로' });
    expect(back.closest('.auth-card')).toBeNull();
    fireEvent.click(back);
    expect(onLogout).toHaveBeenCalled();
  });

  it('기기 분실 안내: 운영자에게 MFA 초기화 요청', () => {
    renderChallenge();
    expect(screen.getByText('기기를 잃어버렸나요? 운영자에게 MFA 초기화를 요청하세요.')).toBeInTheDocument();
  });

  it('TOTP 칸은 숫자 아닌 입력을 무시하고, 붙여넣기는 숫자만 6자리로 자른다', () => {
    renderChallenge();
    fireEvent.change(cell(0), { target: { value: 'a' } });
    expect(cell(0).value).toBe('');
    fireEvent.paste(cell(0), { clipboardData: { getData: () => 'a1b2c3d4e5f6g7' } });
    expect(cellValues()).toBe('123456');
  });

  it('6자리를 모두 입력하면 verify 를 정확히 한 번 호출한다(자동 제출)', async () => {
    let resolve!: (v: { verified: boolean }) => void;
    vi.mocked(mfaApi.verify).mockReturnValue(new Promise((r) => { resolve = r; }));
    const { onVerified } = renderChallenge();
    '123456'.split('').forEach((d, i) => fireEvent.change(cell(i), { target: { value: d } }));
    // 진행 중 수동 확인(버튼·Enter)이 겹쳐도 중복 호출하지 않는다
    fireEvent.submit(screen.getByRole('button', { name: /확인/ }).closest('form')!);
    expect(mfaApi.verify).toHaveBeenCalledTimes(1);
    expect(mfaApi.verify).toHaveBeenCalledWith('123456');
    resolve({ verified: true });
    await waitFor(() => expect(onVerified).toHaveBeenCalledTimes(1));
    expect(mfaApi.verify).toHaveBeenCalledTimes(1);
  });

  it('수동 확인 버튼은 6자리 전에는 비활성, 실패하면 칸을 비우고 카드 안 경고(토스트 없음)', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    fireEvent.change(cell(0), { target: { value: '1' } });
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('코드가 맞지 않습니다.');
    expect(alert).toHaveTextContent('앱에 지금 표시된 코드를 다시 입력하세요.');
    expect(alert.closest('.auth-card')).not.toBeNull();
    expect(document.querySelector('.toast')).toBeNull();
    expect(screen.getByText(/휴대폰 시간 자동 설정을 확인하세요/)).toBeInTheDocument();
    await waitFor(() => expect(cellValues()).toBe(''));
  });

  it('검증 실패 후 첫 칸에 다시 포커스 — 클릭 없이 바로 다시 입력', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await waitFor(() => expect(cell(0)).toBeEnabled());
    await waitFor(() => expect(document.activeElement).toBe(cell(0)));
  });

  it('서버·네트워크 오류는 코드 오류가 아니라 "확인하지 못했습니다"', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(new ApiError(503, 'error', 'error'));
    renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '123456' } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('확인하지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).not.toHaveTextContent('코드가 맞지 않습니다');
  });

  it('복구 코드가 틀리면 경고 + 복구 코드 칸을 비우고 포커스', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    toRecovery();
    const rec = screen.getByLabelText('복구 코드') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: 'ab3f2k7m' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('복구 코드가 맞지 않습니다.');
    expect(rec.value).toBe('');
    expect(document.activeElement).toBe(rec);
  });

  it('모드를 바꾸면 경고가 사라진다', async () => {
    vi.mocked(mfaApi.verify).mockRejectedValue(wrongCode());
    renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '999999' } });
    await screen.findByRole('alert');
    toRecovery();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('복구 코드 모드는 같은 카드 안에서 전환되고 대문자·대시로 정형한다', () => {
    const { container } = renderChallenge();
    const card = container.querySelector('.auth-card');
    toRecovery();
    expect(container.querySelector('.auth-card')).toBe(card);
    expect(screen.getByRole('heading', { name: '복구 코드로 로그인' })).toBeInTheDocument();
    const rec = screen.getByLabelText('복구 코드') as HTMLInputElement;
    expect(rec).toHaveAttribute('placeholder', 'AB3F-2K7M');
    fireEvent.change(rec, { target: { value: 'ab3f2k7m' } });
    expect(rec.value).toBe('AB3F-2K7M');
  });

  it('TOTP 로 돌아가면 입력이 비워진다', () => {
    renderChallenge();
    toRecovery();
    fireEvent.change(screen.getByLabelText('복구 코드'), { target: { value: 'abcd' } });
    fireEvent.click(screen.getByRole('button', { name: /authenticator 코드로 돌아가기/ }));
    expect(cellValues()).toBe('');
  });

  it('복구 코드가 부분 입력이면 확인 비활성', () => {
    renderChallenge();
    toRecovery();
    fireEvent.change(screen.getByLabelText('복구 코드'), { target: { value: 'ab3f2' } });
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled();
  });

  it('복구 코드가 완성되면 확인 활성, 제출 시 그 코드로 verify', async () => {
    const { onVerified } = renderChallenge();
    toRecovery();
    fireEvent.change(screen.getByLabelText('복구 코드'), { target: { value: 'ab3f2k7m' } });
    const btn = screen.getByRole('button', { name: '확인' });
    expect(btn).not.toBeDisabled();
    fireEvent.click(btn);
    await waitFor(() => expect(onVerified).toHaveBeenCalled());
    expect(mfaApi.verify).toHaveBeenCalledWith('AB3F-2K7M');
  });

  it('검증 중에는 입력이 읽기 전용이라 늦은 실패 응답이 새로 입력한 값을 지우지 못한다', async () => {
    let reject!: (e: unknown) => void;
    vi.mocked(mfaApi.verify).mockReturnValue(new Promise((_, r) => { reject = r; }));
    renderChallenge();
    toRecovery();
    const rec = screen.getByLabelText('복구 코드') as HTMLInputElement;
    fireEvent.change(rec, { target: { value: 'ab3f2k7m' } });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    expect(rec).toHaveAttribute('readonly');
    reject(wrongCode());
    expect(await screen.findByRole('alert')).toHaveTextContent('복구 코드가 맞지 않습니다.');
    expect(rec).not.toHaveAttribute('readonly');
  });

  it('TOTP 검증 중에는 칸이 비활성이다', async () => {
    let resolve!: (v: { verified: boolean }) => void;
    vi.mocked(mfaApi.verify).mockReturnValue(new Promise((r) => { resolve = r; }));
    const { onVerified } = renderChallenge();
    fireEvent.paste(cell(0), { clipboardData: { getData: () => '123456' } });
    expect(cell(0)).toBeDisabled();
    resolve({ verified: true });
    await waitFor(() => expect(onVerified).toHaveBeenCalled());
  });
});
