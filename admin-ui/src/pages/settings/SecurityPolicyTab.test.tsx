import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, waitFor, within, act, cleanup } from '@testing-library/react';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { SecurityPolicyView } from '@/api/securityPolicy';
import { ApiError } from '@/api/types';

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

  it('CORS: Enter 로 추가(새 칩 강조), 중복은 안내만, 취소는 서버 값으로 되돌림', async () => {
    await setup();
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: ' https://new.example.com ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('https://new.example.com').closest('.chip')).toHaveClass('chip--added');
    fireEvent.change(input, { target: { value: 'https://admin.example.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText('https://admin.example.com')).toHaveLength(1);
    expect(screen.getByText('이미 목록에 있습니다.')).toBeInTheDocument();
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

describe('SecurityPolicyTab V3', () => {
  it('로드 실패 → 기본값 대신 LoadError, 입력칸·저장 숨김, 다시 시도로 복구', async () => {
    mock(securityPolicyApi.get).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderWithDataRouter(<ToastHost><SecurityPolicyTab /></ToastHost>, { initialPath: '/settings?tab=security' });
    expect(await screen.findByText('보안 정책을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByLabelText('세션 idle timeout (분)')).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();
    expect(document.querySelector('.toast')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByLabelText('세션 idle timeout (분)')).toHaveValue(30));
  });

  it('timeout 은 1–1440 — 범위 밖·빈 값은 인라인 오류, 저장해도 API 를 부르지 않고 입력칸으로 포커스', async () => {
    await setup();
    const input = screen.getByLabelText('세션 idle timeout (분)');
    const ERR = '1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.';
    expect(screen.getByText('1–1440분 (최대 24시간)')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '0' } });
    expect(screen.getByText(ERR)).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    expect(securityPolicyApi.update).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: '1441' } });
    expect(screen.getByText(ERR)).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '' } });
    expect(screen.getByText(ERR)).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '1440' } });
    expect(screen.queryByText(ERR)).toBeNull();
  });

  it('CORS origin 형식 오류면 추가하지 않고 입력값을 남긴다', async () => {
    await setup();
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: 'admin.example.com/login' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com')).toBeInTheDocument();
    expect(input).toHaveValue('admin.example.com/login');
    expect(bar()).toBeNull();
    expect(screen.getByText('https://호스트[:포트] 형식. 경로 · 와일드카드(*) 불가.')).toBeInTheDocument();
  });

  it('CORS origin 은 소문자로 맞춰 저장하고, 대소문자만 다른 중복은 안내', async () => {
    await setup();
    const input = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(input, { target: { value: ' HTTPS://New.Example.com ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('https://new.example.com')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'https://ADMIN.example.com' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByText('이미 목록에 있습니다.')).toBeInTheDocument();
  });

  it('현재 콘솔 origin 을 지우면 경고 + 삭제 되돌리기, 그래도 저장하면 L2 확인 후 저장', async () => {
    const origin = window.location.origin;
    mock(securityPolicyApi.get).mockResolvedValue({ ...view, corsAllowlist: [origin, 'https://admin.example.com'] });
    mock(securityPolicyApi.update).mockResolvedValue({ ...view, corsAllowlist: ['https://admin.example.com'] });
    await setup();
    expect(screen.queryByText(/지금 쓰고 있는 콘솔 주소입니다/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: `${origin} 삭제` }));
    expect(screen.getByText(/지금 쓰고 있는 콘솔 주소입니다/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '삭제 되돌리기' }));
    expect(screen.queryByText(/지금 쓰고 있는 콘솔 주소입니다/)).toBeNull();
    expect(bar()).toBeNull(); // 원래 순서로 되돌려 변경 0건

    fireEvent.click(screen.getByRole('button', { name: `${origin} 삭제` }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    const dlg = screen.getByRole('alertdialog', { name: '현재 콘솔 주소를 빼고 저장할까요?' });
    expect(securityPolicyApi.update).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    fireEvent.click(within(dlg).getByRole('button', { name: '저장' }));
    await waitFor(() => expect(securityPolicyApi.update).toHaveBeenCalledWith({
      sessionIdleTimeoutMinutes: 30, mfaRequired: true, corsAllowlist: ['https://admin.example.com'],
    }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });

  it('저장 중에는 입력을 바꿀 수 없고 저장은 한 번만 나간다', async () => {
    let resolve!: (v: SecurityPolicyView) => void;
    mock(securityPolicyApi.update).mockReturnValue(new Promise<SecurityPolicyView>((r) => { resolve = r; }));
    await setup();
    fireEvent.change(screen.getByLabelText('세션 idle timeout (분)'), { target: { value: '45' } });
    const save = within(bar()!).getByRole('button', { name: '저장' });
    fireEvent.click(save);
    fireEvent.click(save);
    expect(securityPolicyApi.update).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('세션 idle timeout (분)')).toBeDisabled();
    expect(screen.getByRole('switch', { name: '모든 운영자에게 MFA 필수' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'origin 추가' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'https://admin.example.com 삭제' })).toBeDisabled();
    await act(async () => { resolve({ ...view, sessionIdleTimeoutMinutes: 45 }); });
    await waitFor(() => expect(screen.getByLabelText('세션 idle timeout (분)')).toBeEnabled());
  });

  it('저장 실패는 describeError 토스트로, 입력은 그대로', async () => {
    mock(securityPolicyApi.update).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    await setup();
    fireEvent.change(screen.getByLabelText('세션 idle timeout (분)'), { target: { value: '45' } });
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장' }));
    expect(await screen.findByText('보안 정책을 저장하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByLabelText('세션 idle timeout (분)')).toHaveValue(45);
  });
});
