// admin-ui/src/pages/settings/AccountTab.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, waitFor, within, cleanup, act } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { useState, type ReactElement } from 'react';
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

const CODE_MISMATCH = '코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요.';

const renderHost = (ui: ReactElement) => renderWithDataRouter(ui, { initialPath: '/settings?tab=account' });
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
    const { container } = renderHost(<Host initial={off} />);
    expect(screen.getByText('꺼짐')).toHaveClass('badge');
    expect(screen.getByText('계정을 한 단계 더 보호하세요')).toBeInTheDocument();
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
    expect(screen.getByRole('button', { name: '2단계 인증 켜기' })).toBeInTheDocument();
  });

  it('stepper progression: QR 스캔 → 코드 확인 → 복구 코드 다이얼로그', async () => {
    renderHost(<Host initial={off} />);
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

  it('confirm 성공 후 getMe 가 실패해도 복구 코드는 보여 준다', async () => {
    m(getMe).mockRejectedValue(new Error('boom'));
    renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    const rc = await screen.findByRole('alertdialog', { name: '복구 코드 — 지금만 표시됩니다' });
    expect(within(rc).getByText('AAAA-BBBB')).toBeInTheDocument();
    expect(screen.queryByText('2단계 인증 확인 실패')).toBeNull();
  });

  it('잘못된 코드면 다이얼로그 안 인라인 오류 + 코드 지움 + 첫 칸 포커스, 토스트 없음', async () => {
    const { ApiError } = await import('@/api/types');
    m(mfaApi.confirm).mockRejectedValue(new ApiError(401, 'invalid_code', 'invalid_code'));
    renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '000000' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    expect(await within(dlg).findByText(CODE_MISMATCH)).toBeInTheDocument();
    const first = within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' });
    expect(first).toHaveValue('');
    await waitFor(() => expect(document.activeElement).toBe(first));
    expect(screen.getByRole('dialog', { name: '2단계 인증 켜기' })).toBeInTheDocument();
    expect(document.querySelector('.toast')).toBeNull();
    // 다시 입력하면 오류가 사라진다
    fireEvent.change(first, { target: { value: '1' } });
    expect(within(dlg).queryByText(CODE_MISMATCH)).toBeNull();
  });

  it('네트워크 오류는 코드 불일치가 아니라 쉬운 오류 문구로 인라인 표시', async () => {
    m(mfaApi.confirm).mockRejectedValue(new TypeError('Failed to fetch'));
    renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    expect(await within(dlg).findByText(/네트워크에 연결할 수 없습니다/)).toBeInTheDocument();
    expect(within(dlg).queryByText(CODE_MISMATCH)).toBeNull();
  });

  it('취소하면 다이얼로그가 닫히고 confirm 을 부르지 않는다', async () => {
    renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.click(within(dlg).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('dialog', { name: '2단계 인증 켜기' })).toBeNull();
    expect(mfaApi.confirm).not.toHaveBeenCalled();
  });
});

describe('AccountTab — MFA 켜짐', () => {
  it('optional rows hidden when undefined (§6 ④)', () => {
    renderHost(<Host initial={on} />);
    expect(screen.getByText('켜짐')).toHaveClass('badge--success');
    expect(screen.queryByText('등록일')).toBeNull();
    expect(screen.queryByText('남은 복구 코드')).toBeNull();
    expect(screen.getByText('위험 영역')).toBeInTheDocument();
  });

  it('등록일은 서버 값이 있을 때만, 복구 코드는 개수 대신 보관 안내', () => {
    renderHost(<Host initial={{ ...on, mfaEnrolledAt: '2026-08-31T20:00:00Z', recoveryCodesRemaining: 7 }} />);
    expect(screen.getByText('등록일')).toBeInTheDocument();
    expect(screen.getByText('2026-09-01')).toBeInTheDocument(); // UTC 08-31 20:00 = KST 09-01 05:00
    expect(screen.queryByText('남은 복구 코드')).toBeNull();
    expect(screen.queryByText('7 / 10')).toBeNull();
    expect(screen.getByText('복구 코드 보관')).toBeInTheDocument();
    expect(screen.getByText(/한 번만 표시됩니다/)).toBeInTheDocument();
  });

  it('2단계 인증 끄기: alertdialog, 6자리 입력 후 disable(code)', async () => {
    renderHost(<Host initial={on} />);
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

  it('끄기 코드가 틀리면 다이얼로그 안 인라인 오류, 입력값 유지 + 전체 선택 + 포커스, 토스트 없음', async () => {
    const { ApiError } = await import('@/api/types');
    m(mfaApi.disable).mockRejectedValue(new ApiError(401, 'invalid_code', 'invalid_code'));
    renderHost(<Host initial={on} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 끄기' }));
    const dlg = screen.getByRole('alertdialog', { name: '2단계 인증 끄기' });
    const input = within(dlg).getByLabelText('인증 코드') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '끄기' }));
    expect(await within(dlg).findByText(CODE_MISMATCH)).toBeInTheDocument();
    expect(input).toHaveValue('123456');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(6);
    expect(document.querySelector('.toast')).toBeNull();
    expect(screen.getByRole('alertdialog', { name: '2단계 인증 끄기' })).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '65432' } });
    expect(within(dlg).queryByText(CODE_MISMATCH)).toBeNull();
  });

  it('끄기 요청 중에는 코드 입력이 잠기고(읽기 전용), 늦은 실패가 다시 친 값을 덮지 않는다', async () => {
    const { ApiError } = await import('@/api/types');
    let rejectDisable!: (e: unknown) => void;
    m(mfaApi.disable).mockReturnValue(new Promise((_, rej) => { rejectDisable = rej; }));
    renderHost(<Host initial={on} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 끄기' }));
    const dlg = screen.getByRole('alertdialog', { name: '2단계 인증 끄기' });
    const input = within(dlg).getByLabelText('인증 코드') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '끄기' }));
    await waitFor(() => expect(mfaApi.disable).toHaveBeenCalledTimes(1));
    expect(input).toHaveAttribute('readonly');
    expect(within(dlg).getByRole('button', { name: '취소' })).toBeDisabled();
    fireEvent.change(input, { target: { value: '999999' } }); // 잠겨 있어도 이벤트가 오면 무시
    expect(input).toHaveValue('123456');
    await act(async () => { rejectDisable(new ApiError(401, 'invalid_code', 'invalid_code')); await new Promise((r) => setTimeout(r, 0)); });
    expect(within(dlg).getByText(CODE_MISMATCH)).toBeInTheDocument();
    expect(input).not.toHaveAttribute('readonly');
    expect(input).toHaveValue('123456');
  });
});

describe('AccountTab — 복구 코드 이동 가드', () => {
  it('복구 코드가 표시된 동안 이동·새로고침을 막는다', async () => {
    const { router } = renderHost(<Host initial={off} />);
    fireEvent.click(screen.getByRole('button', { name: '2단계 인증 켜기' }));
    const dlg = await screen.findByRole('dialog', { name: '2단계 인증 켜기' });
    fireEvent.paste(within(dlg).getByRole('textbox', { name: '인증 코드 1번째 자리' }), { clipboardData: { getData: () => '123456' } });
    fireEvent.click(within(dlg).getByRole('button', { name: '확인 → 복구 코드' }));
    await screen.findByRole('alertdialog', { name: '복구 코드 — 지금만 표시됩니다' });

    const ev = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);

    await act(async () => { await router.navigate('/tenants'); });
    expect(screen.getByRole('alertdialog', { name: '복구 코드를 아직 저장하지 않았습니다' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/settings');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(screen.getByRole('alertdialog', { name: '복구 코드 — 지금만 표시됩니다' })).toBeInTheDocument();
  });
});
