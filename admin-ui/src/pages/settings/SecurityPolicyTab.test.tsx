import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, waitFor, within, act, cleanup } from '@testing-library/react';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { SecurityPolicyView } from '@/api/securityPolicy';

vi.mock('@/api/securityPolicy', () => ({ securityPolicyApi: { get: vi.fn(), update: vi.fn() } }));

import { securityPolicyApi } from '@/api/securityPolicy';
import SecurityPolicyTab from './SecurityPolicyTab';

const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>;
const view: SecurityPolicyView = {
  sessionIdleTimeoutMinutes: 30, mfaRequired: true, corsAllowlist: ['https://admin.example.com'],
  updatedAt: '2026-10-01T00:00:00Z', updatedBy: null,
};
const bar = () => screen.queryByRole('region', { name: '저장되지 않은 변경' });

beforeEach(() => {
  vi.clearAllMocks();
  mock(securityPolicyApi.get).mockResolvedValue(view);
});
afterEach(cleanup);

async function setup() {
  const r = renderWithDataRouter(<ToastHost><SecurityPolicyTab /></ToastHost>, { initialPath: '/settings?tab=security' });
  await waitFor(() => expect(screen.getByLabelText('세션 idle timeout (분)')).toHaveValue(30));
  return r;
}

describe('SecurityPolicyTab V2', () => {
  it('카드 헤더: 제목 옆 캡션 "모든 콘솔 운영자에게 적용"(card__cap)', async () => {
    await setup();
    expect(screen.getByText('모든 콘솔 운영자에게 적용')).toHaveClass('card__cap');
  });

  it('.set 3행 + 변경 없으면 SaveBar 없음', async () => {
    await setup();
    expect(screen.getByRole('switch', { name: '모든 운영자에게 MFA 필수' })).toBeChecked();
    expect(screen.getByText('https://admin.example.com')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'origin 추가' })).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('dirty → SaveBar(건수·요약), 저장하면 update 후 사라짐', async () => {
    await setup();
    fireEvent.change(screen.getByLabelText('세션 idle timeout (분)'), { target: { value: '45' } });
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('세션 idle timeout');

    fireEvent.click(screen.getByRole('switch', { name: '모든 운영자에게 MFA 필수' }));
    expect(bar()).toHaveTextContent('저장하지 않은 변경 2건');

    mock(securityPolicyApi.update).mockResolvedValue({ ...view, sessionIdleTimeoutMinutes: 45, mfaRequired: false });
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    await waitFor(() => expect(securityPolicyApi.update).toHaveBeenCalledWith({
      sessionIdleTimeoutMinutes: 45, mfaRequired: false, corsAllowlist: ['https://admin.example.com'],
    }));
    await waitFor(() => expect(bar()).toBeNull());
    expect(await screen.findByText('보안 정책 저장됨')).toBeInTheDocument();
    expect(screen.queryByText(/traceId/)).toBeNull();
  });

  it('CORS: Enter 로 추가(새 칩 강조), 중복 무시, 취소는 서버 값으로 되돌림', async () => {
    await setup();
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: ' https://new.example.com ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('https://new.example.com').closest('.chip')).toHaveClass('chip--added');
    fireEvent.change(input, { target: { value: 'https://admin.example.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://admin.example.com')).toHaveLength(1);
    expect(bar()).toHaveTextContent('CORS Allowlist');

    fireEvent.click(within(bar()!).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(securityPolicyApi.get).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.queryByText('https://new.example.com')).toBeNull();
  });

  it('dirty 상태에서 탭 이동(?tab=)은 이탈 확인으로 막힌다', async () => {
    const { router } = await setup();
    fireEvent.change(screen.getByLabelText('세션 idle timeout (분)'), { target: { value: '10' } });
    await act(async () => { await router.navigate('/settings?tab=system'); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=security');
  });
});
