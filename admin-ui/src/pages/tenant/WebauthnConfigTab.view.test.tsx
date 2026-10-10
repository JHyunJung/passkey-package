// admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act, waitFor, cleanup } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant } from '@/api/designTypes';

vi.mock('@/api/webauthn', () => ({ webauthnApi: { get: vi.fn(), diff: vi.fn(), update: vi.fn() } }));

import { webauthnApi } from '@/api/webauthn';
import WebauthnConfigTab, { changedFieldNames } from './WebauthnConfigTab';

const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'dev-passkey.crosscert.com', status: 'ACTIVE', credentials: 0, apiKeys: 1,
  lastEventAt: null, createdAt: '2026-03-01T00:00:00Z', userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const CFG = {
  rpId: 'dev-passkey.crosscert.com', rpName: 'Dev', origins: ['https://dev-passkey.crosscert.com'], formats: ['none', 'packed'],
  userVerification: 'REQUIRED' as const, attestationConveyance: 'NONE' as const, timeoutMs: 60000, _mdsRequired: true,
};

const onSaved = vi.fn();
function Host() {
  const nav = useNavigate();
  return (
    <ToastHost>
      <WebauthnConfigTab tenant={T} onSaved={onSaved} />
      <button type="button" onClick={() => nav('/tenants/t-1?tab=aaguid')}>AAGUID 탭으로</button>
    </ToastHost>
  );
}
const renderTab = () => renderWithDataRouter(<Host />, { initialPath: '/tenants/t-1?tab=webauthn' });
const bar = () => screen.queryByRole('region', { name: '저장되지 않은 변경' });

beforeEach(() => {
  vi.clearAllMocks();
  (webauthnApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, origins: [...CFG.origins] });
});
afterEach(cleanup);

describe('changedFieldNames', () => {
  it('화면 순서로 정렬', () => {
    expect(changedFieldNames([{ key: 'origins' }, { key: 'timeoutMs' }])).toEqual(['timeoutMs', 'origins']);
  });
});

describe('WebauthnConfigTab V2', () => {
  it('설명 좌/컨트롤 우 행, rpId 는 읽기 전용 + 변경 불가', async () => {
    renderTab();
    const rpId = await screen.findByLabelText('rpId');
    expect(rpId).toHaveAttribute('readonly');
    expect(rpId).toHaveValue('dev-passkey.crosscert.com');
    expect(screen.getByText('변경 불가')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'WebAuthn Configuration' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'userVerification' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'REQUIRED' })).toHaveAttribute('aria-pressed', 'true');
    expect(bar()).toBeNull();
    expect(screen.queryByText(/변경 사항 \d+건/)).toBeNull();
  });

  it('dirty → SaveBar 건수·요약, 변경 필드 표시, 실시간 timeout 안내, 되돌리기', async () => {
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '120000' } });
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('timeoutMs');
    expect(screen.getAllByText('변경됨')).toHaveLength(1);
    expect(screen.getByText(/= 120초 \(2분\)/)).toBeInTheDocument();

    const origin = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(origin, { target: { value: 'sub.dev-passkey.crosscert.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText('https://sub.dev-passkey.crosscert.com')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 2건');
    expect(bar()).toHaveTextContent('timeoutMs · origins');

    fireEvent.click(within(bar()!).getByRole('button', { name: '되돌리기' }));
    expect(bar()).toBeNull();
    expect(screen.queryByText('https://sub.dev-passkey.crosscert.com')).toBeNull();
  });

  it('origin 검증: rpId 범위 밖·중복(정규화 후)은 추가하지 않고 오류 표시', async () => {
    renderTab();
    const origin = await screen.findByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(origin, { target: { value: 'evil.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText(/범위를 벗어납니다/)).toBeInTheDocument();
    fireEvent.change(origin, { target: { value: 'dev-passkey.crosscert.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText("'https://dev-passkey.crosscert.com' 는 이미 추가되어 있습니다.")).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('Android 앱 키: 64 hex 지문 → apk-key-hash origin, 잘못된 길이는 오류', async () => {
    renderTab();
    const apk = await screen.findByRole('textbox', { name: 'Android APK 키 해시' });
    fireEvent.change(apk, { target: { value: 'abc' } });
    fireEvent.click(screen.getByRole('button', { name: '앱 키 추가' }));
    expect(screen.getByText('SHA-256 지문은 32바이트(64 hex)여야 합니다. 입력 길이: 3')).toBeInTheDocument();
    fireEvent.change(apk, { target: { value: '00:'.repeat(31) + '00' } });
    fireEvent.click(screen.getByRole('button', { name: '앱 키 추가' }));
    expect(screen.getByText(`android:apk-key-hash:${'A'.repeat(43)}`)).toBeInTheDocument();
    expect(bar()).toHaveTextContent('origins');
  });

  it('dirty 가 아니면 탭 이동이 바로 된다', async () => {
    const { router } = renderTab();
    await screen.findByLabelText('timeoutMs');
    await act(async () => { fireEvent.click(screen.getByText('AAGUID 탭으로')); });
    expect(router.state.location.search).toBe('?tab=aaguid');
  });

  it('dirty 면 ?tab=aaguid 이동이 막히고 확인 다이얼로그', async () => {
    const { router } = renderTab();
    fireEvent.change(await screen.findByLabelText('rpName'), { target: { value: 'Dev 2' } });
    await act(async () => { fireEvent.click(screen.getByText('AAGUID 탭으로')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=webauthn');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
    expect(screen.getByLabelText('rpName')).toHaveValue('Dev 2');
  });

  it('저장… → diff 확인 다이얼로그 → 기존과 같은 payload 로 update', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([{ key: 'timeoutMs', from: 60000, to: 120000 }]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, timeoutMs: 120000 });
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(webauthnApi.diff).toHaveBeenCalledWith('t-1', { ...CFG, timeoutMs: 120000 }, true);
    const dlg = screen.getByRole('dialog', { name: '변경 사항 확인' });
    expect(within(dlg).getByText('60000')).toBeInTheDocument();
    expect(within(dlg).getByText('120000')).toBeInTheDocument();
    expect(within(dlg).getByText('WEBAUTHN_CONFIG_UPDATED')).toBeInTheDocument();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '1개 변경 사항 저장' })); });
    expect(webauthnApi.update).toHaveBeenCalledWith('t-1', 'Acme', { ...CFG, timeoutMs: 120000 }, true);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.queryByRole('dialog', { name: '변경 사항 확인' })).toBeNull();
    expect(screen.getByText('WebAuthn config 저장됨')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledTimes(1);
  });

  it('diff 가 비면 "변경 사항이 없습니다."', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    renderTab();
    fireEvent.change(await screen.findByLabelText('timeoutMs'), { target: { value: '90000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(within(screen.getByRole('dialog', { name: '변경 사항 확인' })).getByText('변경 사항이 없습니다.')).toBeInTheDocument();
  });
});
