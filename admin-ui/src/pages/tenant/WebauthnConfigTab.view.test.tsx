// admin-ui/src/pages/tenant/WebauthnConfigTab.view.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act, waitFor, cleanup } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant } from '@/api/designTypes';
import { ApiError } from '@/api/types';

vi.mock('@/config/links', () => ({ HELP_LINKS: { rpGuide: 'https://docs.example/rp', apiDocs: '', support: '' } }));
vi.mock('@/api/webauthn', () => ({ webauthnApi: { get: vi.fn(), diff: vi.fn(), update: vi.fn() } }));

import { HELP_LINKS } from '@/config/links';
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
    const rpId = await screen.findByLabelText('RP ID');
    expect(rpId).toHaveAttribute('readonly');
    expect(rpId).toHaveValue('dev-passkey.crosscert.com');
    expect(screen.getByText('변경 불가')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'WebAuthn 설정' })).toBeInTheDocument();
    expect(screen.getByText('변경 사항은 저장 후 다음 패스키 등록·인증부터 적용됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '사용자 검증' })).toBeInTheDocument();
    expect(screen.getByText('대기 시간')).toHaveAttribute('title', 'timeoutMs');
    expect(within(screen.getByRole('button', { name: '필수' })).getByText('필수')).toHaveAttribute('title', 'REQUIRED');
    expect(screen.getByRole('button', { name: '필수' })).toHaveAttribute('aria-pressed', 'true');
    expect(bar()).toBeNull();
    expect(screen.queryByText(/변경 사항 \d+건/)).toBeNull();
  });

  it('dirty → SaveBar 건수·요약, 변경 필드 표시, 실시간 timeout 안내, 되돌리기', async () => {
    renderTab();
    fireEvent.change(await screen.findByLabelText('대기 시간'), { target: { value: '120000' } });
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('대기 시간');
    expect(screen.getAllByText('변경됨')).toHaveLength(1);
    expect(screen.getByText(/= 120초 \(2분\)/)).toBeInTheDocument();

    const origin = screen.getByRole('textbox', { name: 'origin 추가' });
    fireEvent.change(origin, { target: { value: 'sub.dev-passkey.crosscert.com' } });
    fireEvent.keyDown(origin, { key: 'Enter' });
    expect(screen.getByText('https://sub.dev-passkey.crosscert.com')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 2건');
    expect(bar()).toHaveTextContent('대기 시간 · 허용 origin');

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
    expect(bar()).toHaveTextContent('허용 origin');
  });

  it('dirty 가 아니면 탭 이동이 바로 된다', async () => {
    const { router } = renderTab();
    await screen.findByLabelText('대기 시간');
    await act(async () => { fireEvent.click(screen.getByText('AAGUID 탭으로')); });
    expect(router.state.location.search).toBe('?tab=aaguid');
  });

  it('dirty 면 ?tab=aaguid 이동이 막히고 확인 다이얼로그', async () => {
    const { router } = renderTab();
    fireEvent.change(await screen.findByLabelText('RP 표시 이름'), { target: { value: 'Dev 2' } });
    await act(async () => { fireEvent.click(screen.getByText('AAGUID 탭으로')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=webauthn');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '머무르기' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
    expect(screen.getByLabelText('RP 표시 이름')).toHaveValue('Dev 2');
  });

  it('저장… → L2 변경 비교(화면 라벨·초 단위) → 기존과 같은 payload 로 update', async () => {
    // 실제 서버 diff 는 timeoutMs·attestationConveyance 를 비교하지 않는다 → 빈 목록.
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, timeoutMs: 120000 });
    renderTab();
    fireEvent.change(await screen.findByLabelText('대기 시간'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    expect(webauthnApi.diff).toHaveBeenCalledWith('t-1', { ...CFG, timeoutMs: 120000 }, true);
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(within(dlg).getByText('다음 패스키 등록·인증부터 적용됩니다.')).toBeInTheDocument();
    expect(within(dlg).getByText('대기 시간')).toHaveAttribute('title', 'timeoutMs');
    expect(within(dlg).getByText('60초')).toBeInTheDocument();
    expect(within(dlg).getByText('120초')).toBeInTheDocument();
    expect(within(dlg).getByText(/WebAuthn 설정 변경/)).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(webauthnApi.update).toHaveBeenCalledWith('t-1', 'Acme', { ...CFG, timeoutMs: 120000 }, true);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByText('WebAuthn 설정을 저장했습니다')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledTimes(1);
  });

  it('서버 diff 가 비어도 로컬 변경(대기 시간)이 있으면 저장을 막지 않고 확인 목록에 보인다', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...CFG, timeoutMs: 90000 });
    renderTab();
    fireEvent.change(await screen.findByLabelText('대기 시간'), { target: { value: '90000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(within(dlg).getByText('60초')).toBeInTheDocument();
    expect(within(dlg).getByText('90초')).toBeInTheDocument();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(webauthnApi.update).toHaveBeenCalledTimes(1);
  });

  it('서버 diff(requireUserVerification 불리언)와 로컬 변경(인증기 증명)을 합쳐 한국어로 보인다', async () => {
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([{ key: 'requireUserVerification', from: true, to: false }]);
    renderTab();
    await screen.findByLabelText('대기 시간');
    fireEvent.click(screen.getByRole('button', { name: '권장' }));
    fireEvent.click(screen.getByRole('button', { name: '직접' }));
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 2개를 저장할까요?' });
    expect(within(dlg).getByText('사용자 검증')).toHaveAttribute('title', 'requireUserVerification');
    expect(within(dlg).getByText('필수')).toBeInTheDocument();
    expect(within(dlg).getByText('권장')).toBeInTheDocument();
    expect(within(dlg).getByText('인증기 증명')).toBeInTheDocument();
    expect(within(dlg).getByText('요청 안 함')).toBeInTheDocument();
    expect(within(dlg).getByText('직접')).toBeInTheDocument();
    expect(dlg).not.toHaveTextContent(/requireUserVerification|true|false/);
  });

  it('저장 실패는 다이얼로그 안 인라인 오류, 입력과 SaveBar 는 그대로', async () => {
    // 실제 서버 diff 는 timeoutMs·attestationConveyance 를 비교하지 않는다 → 빈 목록.
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.change(await screen.findByLabelText('대기 시간'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(within(dlg).getByText('저장하지 못했습니다')).toBeInTheDocument();
    expect(within(dlg).getByText(/서버가 잠시 응답하지 않습니다/)).toBeInTheDocument();
    expect(screen.getByLabelText('대기 시간')).toHaveValue(120000);
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('저장 중에는 입력을 잠그고 저장이 두 번 나가지 않는다', async () => {
    // 실제 서버 diff 는 timeoutMs·attestationConveyance 를 비교하지 않는다 → 빈 목록.
    (webauthnApi.diff as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    let resolve!: (v: unknown) => void;
    (webauthnApi.update as ReturnType<typeof vi.fn>).mockReturnValue(new Promise((r) => { resolve = r; }));
    renderTab();
    fireEvent.change(await screen.findByLabelText('대기 시간'), { target: { value: '120000' } });
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' })); });
    const dlg = screen.getByRole('alertdialog');
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장 중…' })); });
    expect(webauthnApi.update).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('대기 시간')).toBeDisabled();
    expect(screen.getByLabelText('RP 표시 이름')).toBeDisabled();
    expect(within(bar()!).getByRole('button', { name: '저장 중…' })).toBeDisabled();
    await act(async () => { resolve({ ...CFG, timeoutMs: 120000 }); });
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.getByLabelText('대기 시간')).not.toBeDisabled();
  });

  it('로드 실패: "Loading…"에 멈추지 않고 LoadError, 다시 시도로 복구', async () => {
    (webauthnApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce({ ...CFG, origins: [...CFG.origins] });
    renderTab();
    expect(await screen.findByText('WebAuthn 설정을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
    expect(screen.queryByText('config 로드 실패')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByLabelText('RP ID')).toHaveValue('dev-passkey.crosscert.com');
  });

  it('로딩 중에는 Skeleton', () => {
    (webauthnApi.get as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    renderTab();
    expect(screen.getByRole('status', { name: 'WebAuthn 설정 불러오는 중' })).toBeInTheDocument();
  });

  it('"자세히" 문서 링크와 RP 연동 가이드 — 주소가 비면 숨긴다', async () => {
    const { unmount } = renderTab();
    await screen.findByLabelText('RP ID');
    expect(screen.getByRole('link', { name: /RP 연동 가이드/ })).toHaveAttribute('href', 'https://docs.example/rp');
    expect(screen.getByRole('link', { name: '허용 origin 자세히' })).toHaveAttribute('href', 'https://docs.example/rp#origins');
    expect(screen.getByRole('link', { name: '사용자 검증 자세히' })).toHaveAttribute('href', 'https://docs.example/rp#user-verification');
    expect(screen.getByRole('link', { name: '인증기 증명 자세히' })).toHaveAttribute('target', '_blank');
    unmount();
    const links = HELP_LINKS as { rpGuide: string };
    links.rpGuide = '';
    try {
      renderTab();
      await screen.findByLabelText('RP ID');
      await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
      expect(screen.queryByRole('link', { name: /자세히|RP 연동 가이드/ })).toBeNull();
    } finally {
      links.rpGuide = 'https://docs.example/rp';
    }
  });

  it('사용자 문구에 ceremony 를 쓰지 않는다', async () => {
    const { container } = renderTab();
    await screen.findByLabelText('RP ID');
    expect(container).not.toHaveTextContent(/ceremony/i);
  });
});
