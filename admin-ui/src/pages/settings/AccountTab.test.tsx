// admin-ui/src/pages/settings/AccountTab.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
import { useState } from 'react';
import type { Me } from '@/api/types';
import { ToastHost } from '@/ui';

vi.mock('@/api/mfa', () => ({ mfaApi: { enroll: vi.fn(), confirm: vi.fn(), disable: vi.fn(), verify: vi.fn() } }));
vi.mock('@/api/client', () => ({ getMe: vi.fn() }));
// jsdom 에는 canvas 가 없다 — QR 렌더는 값만 확인한다.
vi.mock('@/shell/QrCode', () => ({ QrCode: ({ value }: { value: string }) => <div data-testid="qr">{value}</div> }));
vi.mock('@/lib/clipboard', () => ({ copyToClipboard: vi.fn().mockResolvedValue(true) }));

import { mfaApi } from '@/api/mfa';
import { getMe } from '@/api/client';
import AccountTab from './AccountTab';

const off: Me = { email: 'a@x.com', role: 'RP_ADMIN', tenantIds: ['t1'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const on: Me = { ...off, mfaEnabled: true };
const CODES = ['AAAA-BBBB', 'CCCC-DDDD', 'EEEE-FFFF', 'GGGG-HHHH', 'JJJJ-KKKK', 'LLLL-MMMM', 'NNNN-PPPP', 'QQQQ-RRRR', 'SSSS-TTTT', 'UUUU-VVVV'];

const m = <T,>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>;

function Host({ initial }: { initial: Me }) {
  const [me, setMe] = useState(initial);
  return <ToastHost><AccountTab me={me} onMeChange={setMe} /></ToastHost>;
}

beforeEach(() => {
  vi.clearAllMocks();
  m(mfaApi.enroll).mockResolvedValue({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Passkey:a@x.com?secret=JBSWY3DPEHPK3PXP' });
  m(mfaApi.confirm).mockResolvedValue({ confirmed: true, recoveryCodes: CODES });
  m(mfaApi.disable).mockResolvedValue({ disabled: true });
  m(getMe).mockResolvedValue(on);
});
afterEach(cleanup);

describe('AccountTab — MFA 꺼짐', () => {
  it('일러스트(장식) + 2단계 인증 켜기 카드', () => {
    const { container } = render(<Host initial={off} />);
    expect(screen.getByText('꺼짐')).toHaveClass('badge');
    expect(screen.getByText('계정을 한 단계 더 보호하세요')).toBeInTheDocument();
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
    expect(screen.getByRole('button', { name: '2단계 인증 켜기' })).toBeInTheDocument();
  });

  it('stepper progression: QR 스캔 → 코드 확인 → 복구 코드 다이얼로그', async () => {
    render(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });

    // 1단계: QR + 수동 키
    expect(within(dlg).getByTestId('qr')).toHaveTextContent('otpauth://totp/');
    expect(within(dlg).getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
    expect(within(dlg).getByText('QR 스캔').closest('li')).toHaveAttribute('aria-current', 'step');
    expect(within(dlg).getByText('코드 확인').closest('li')).toHaveClass('stepper__item--todo');
    expect(within(dlg).getByText('복구 코드 저장').closest('li')).toHaveClass('stepper__item--todo');
    const confirmBtn = within(dlg).getByRole('button', { name: '확인 → 복구 코드' });
    expect(confirmBtn).toBeDisabled();

    // 2단계: 코드 입력 시작
    const first = within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' });
    fireEvent.change(first, { target: { value: '1' } });
    expect(within(dlg).getByText('QR 스캔').closest('li')).toHaveClass('stepper__item--done');
    expect(within(dlg).getByText('코드 확인').closest('li')).toHaveAttribute('aria-current', 'step');

    fireEvent.paste(first, { clipboardData: { getData: () => '123456' } });
    expect(confirmBtn).not.toBeDisabled();
    fireEvent.click(confirmBtn);

    // 3단계: 복구 코드(alertdialog), 켜기 다이얼로그는 닫힘
    await waitFor(() => expect(mfaApi.confirm).toHaveBeenCalledWith('123456'));
    expect(await screen.findByRole('alertdialog', { name: '복구 코드 — 지금만 표시됩니다' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '2단계 인증 켜기' })).toBeNull();
    expect(getMe).toHaveBeenCalled();
    expect(screen.getByText('켜짐')).toBeInTheDocument();
  });

  it('잘못된 코드면 코드가 지워지고 다이얼로그는 유지', async () => {
    const { ApiError } = await import('@/api/types');
    m(mfaApi.confirm).mockRejectedValue(new ApiError(401, 'invalid_code', 'invalid_code'));
    render(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '000000' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    expect(await screen.findByText('코드가 올바르지 않습니다.')).toBeInTheDocument();
    expect(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' })).toHaveValue('');
    expect(screen.getByRole('dialog', { name: '2단계 인증 켜기' })).toBeInTheDocument();
  });

  it('취소하면 다이얼로그가 닫히고 confirm 을 부르지 않는다', async () => {
    render(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.click(within(dlg).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('dialog', { name: '2단계 인증 켜기' })).toBeNull();
    expect(mfaApi.confirm).not.toHaveBeenCalled();
  });
});

describe('AccountTab — MFA 켜짐', () => {
  it('optional rows hidden when undefined (§6 ④)', () => {
    render(<Host initial={on} />);
    expect(screen.getByText('켜짐')).toHaveClass('badge--success');
    expect(screen.queryByText('등록일')).toBeNull();
    expect(screen.queryByText('남은 복구 코드')).toBeNull();
    expect(screen.getByText('위험 영역')).toBeInTheDocument();
  });

  it('optional rows shown when defined', () => {
    render(<Host initial={{ ...on, mfaEnrolledAt: '2026-09-01T10:00:00Z', recoveryCodesRemaining: 7 }} />);
    expect(screen.getByText('등록일')).toBeInTheDocument();
    expect(screen.getByText('2026-09-01')).toBeInTheDocument();
    expect(screen.getByText('남은 복구 코드')).toBeInTheDocument();
    expect(screen.getByText('7 / 10')).toBeInTheDocument();
  });

  it('2단계 인증 끄기: alertdialog, 6자리 입력 후 disable(code)', async () => {
    render(<Host initial={on} />);
    m(getMe).mockResolvedValue(off);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 끄기' }));
    const dlg = screen.getByRole('alertdialog', { name: '2단계 인증 끄기' });
    const offBtn = within(dlg).getByRole('button', { name: '끄기' });
    expect(offBtn).toBeDisabled();
    fireEvent.change(within(dlg).getByLabelText('인증 코드'), { target: { value: '12 34a56' } });
    expect(within(dlg).getByLabelText('인증 코드')).toHaveValue('123456');
    fireEvent.click(offBtn);
    await waitFor(() => expect(mfaApi.disable).toHaveBeenCalledWith('123456'));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(await screen.findByText('꺼짐')).toBeInTheDocument();
  });
});
