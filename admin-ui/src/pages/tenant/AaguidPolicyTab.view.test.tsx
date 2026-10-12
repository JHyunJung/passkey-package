// admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act, waitFor, cleanup } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';
import { ApiError } from '@/api/types';

vi.mock('@/api/aaguidPolicy', () => ({ aaguidPolicyApi: { get: vi.fn(), update: vi.fn() } }));

import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import AaguidPolicyTab, { aaguidChangedFields } from './AaguidPolicyTab';

const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'passkey.acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 1,
  lastEventAt: null, createdAt: '2026-03-01T00:00:00Z', userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const YUBI = { aaguid: 'ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4', note: null, mdsName: 'YubiKey 5' };
const UNKNOWN = { aaguid: '00000000-0000-0000-0000-000000000001', note: null, mdsName: null };
const POLICY: AaguidPolicy = { mode: 'ALLOWLIST', mdsStrict: true, entries: [YUBI, UNKNOWN] };

function Host({ onSaved }: { onSaved?: (p: AaguidPolicy) => void }) {
  const nav = useNavigate();
  return (
    <ToastHost>
      <AaguidPolicyTab tenant={T} onSaved={onSaved} />
      <button type="button" onClick={() => nav('/tenants/t-1?tab=webauthn')}>WebAuthn 탭으로</button>
    </ToastHost>
  );
}
const renderTab = (onSaved?: (p: AaguidPolicy) => void) => renderWithDataRouter(<Host onSaved={onSaved} />, { initialPath: '/tenants/t-1?tab=aaguid' });
const bar = () => screen.queryByRole('region', { name: '저장되지 않은 변경' });

beforeEach(() => {
  vi.clearAllMocks();
  (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, entries: [...POLICY.entries] });
});
afterEach(cleanup);

describe('aaguidChangedFields', () => {
  it('바뀐 필드 이름', () => {
    expect(aaguidChangedFields(POLICY, POLICY)).toEqual([]);
    expect(aaguidChangedFields(POLICY, { ...POLICY, mode: 'DENYLIST', mdsStrict: false })).toEqual(['mode', 'MDS strict']);
    expect(aaguidChangedFields(POLICY, { ...POLICY, entries: [YUBI] })).toEqual(['AAGUID 목록']);
    expect(aaguidChangedFields(null, POLICY)).toEqual([]);
  });
});

describe('AaguidPolicyTab MDS 엄격 모드 설명', () => {
  it('MDS 등록 여부만 검사한다 — 폐기(REVOKED) 판정 거부를 약속하지 않는다', async () => {
    renderTab();
    await screen.findByRole('radiogroup', { name: 'mode' });
    expect(screen.queryByText(/REVOKED|폐기/)).toBeNull();
    expect(screen.getByText(/MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다/)).toBeInTheDocument();
  });
});

describe('AaguidPolicyTab V2', () => {
  it('mode radiogroup, MDS 확인/미확인 배지, 무엇을·왜·어떻게 담은 경고 제목', async () => {
    renderTab();
    const group = await screen.findByRole('radiogroup', { name: 'mode' });
    expect(within(group).getAllByRole('radio')).toHaveLength(3);
    expect(within(group).getByRole('radio', { name: /허용 목록/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('YubiKey 5')).toBeInTheDocument();
    expect(screen.getByText('MDS 확인')).toHaveClass('badge--success');
    expect(screen.getByText('MDS 에 없음')).toBeInTheDocument();
    expect(screen.getByText('MDS 미확인')).toHaveClass('badge--warning');
    expect(screen.getByText('MDS 미확인 AAGUID 1건 — 인증기 모델을 확인하세요')).toBeInTheDocument();
    expect(screen.queryByText(/탭 점 배지 사유/)).toBeNull();
    expect(screen.getByRole('button', { name: '항목 보기' })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'MDS strict 켜짐' })).toBeChecked();
    expect(bar()).toBeNull();
  });

  it('미확인 항목이 없으면 Alert 가 없다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, entries: [YUBI] });
    renderTab();
    await screen.findByText('YubiKey 5');
    expect(screen.queryByText(/MDS 미확인 AAGUID/)).toBeNull();
  });

  it('추가: 자동 포맷 → + 추가 → SaveBar 저장… → L2 변경 비교 → 기존과 같은 payload, onSaved 호출', async () => {
    const onSaved = vi.fn();
    const added = { aaguid: 'abcdef01-2345-6789-abcd-ef0123456789', note: null, mdsName: null };
    const saved: AaguidPolicy = { ...POLICY, entries: [YUBI, UNKNOWN, { ...added, mdsName: 'Some Key' }] };
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue(saved);
    renderTab(onSaved);
    const input = await screen.findByRole('textbox', { name: 'AAGUID 입력' });
    const addBtn = screen.getByRole('button', { name: '추가' });
    expect(addBtn).toBeDisabled();
    fireEvent.change(input, { target: { value: 'ABCDEF0123456789ABCDEF0123456789' } });
    expect(input).toHaveValue('abcdef01-2345-6789-abcd-ef0123456789');
    fireEvent.click(addBtn);
    expect(input).toHaveValue('');
    expect(screen.getByText('abcdef01-2345-6789-abcd-ef0123456789')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('AAGUID 목록');
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(within(dlg).getByText('+ abcdef01-2345-6789-abcd-ef0123456789')).toBeInTheDocument();
    expect(aaguidPolicyApi.update).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', { mode: 'ALLOWLIST', mdsStrict: true, entries: [YUBI, UNKNOWN, added] });
    expect(onSaved).toHaveBeenCalledWith(saved);
    await waitFor(() => expect(bar()).toBeNull());
    expect(screen.getByText('AAGUID 정책 저장 완료')).toBeInTheDocument();
  });

  it('중복 AAGUID 는 추가되지 않는다', async () => {
    renderTab();
    const input = await screen.findByRole('textbox', { name: 'AAGUID 입력' });
    fireEvent.change(input, { target: { value: YUBI.aaguid.toUpperCase() } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getAllByText(YUBI.aaguid)).toHaveLength(1);
    expect(bar()).toBeNull();
  });

  it('삭제 IconButton → 목록 변경, 되돌리기로 복구', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: `${UNKNOWN.aaguid} 삭제` }));
    expect(screen.queryByText('MDS 에 없음')).toBeNull();
    expect(bar()).toHaveTextContent('AAGUID 목록');
    fireEvent.click(within(bar()!).getByRole('button', { name: '되돌리기' }));
    expect(screen.getByText('MDS 에 없음')).toBeInTheDocument();
    expect(bar()).toBeNull();
  });

  it('전체 허용 선택 시 목록 행이 숨고 SaveBar 요약은 mode', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /전체 허용/ }));
    expect(screen.queryByRole('textbox', { name: 'AAGUID 입력' })).toBeNull();
    expect(bar()).toHaveTextContent('저장하지 않은 변경 1건');
    expect(bar()).toHaveTextContent('mode');
  });

  it('빈 허용 목록: 목록 자리에 빨간 경고 + AAGUID 추가 / 전체 허용으로 되돌리기, 차단 목록은 안내만', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('허용 목록이 비어 있습니다');
    expect(alert).toHaveTextContent('이대로 저장하면 이 tenant의 모든 패스키 등록·인증이 차단됩니다.');
    fireEvent.click(within(alert).getByRole('button', { name: 'AAGUID 추가' }));
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'AAGUID 입력' }));
    fireEvent.click(screen.getByRole('radio', { name: /차단 목록/ }));
    expect(screen.queryByText('허용 목록이 비어 있습니다')).toBeNull();
    expect(screen.getByText('비어 있음 — 이 상태에서는 모든 패스키 등록·인증이 허용됩니다.')).toBeInTheDocument();
    expect(screen.getByText('차단된 AAGUID')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /허용 목록/ }));
    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: '전체 허용으로 되돌리기' }));
    expect(screen.getByRole('radio', { name: /전체 허용/ })).toHaveAttribute('aria-checked', 'true');
  });

  it('MDS strict 스위치 → SaveBar 요약', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('switch', { name: 'MDS strict 켜짐' }));
    expect(screen.getByRole('switch', { name: 'MDS strict 꺼짐' })).not.toBeChecked();
    expect(bar()).toHaveTextContent('MDS strict');
  });

  it('dirty 면 ?tab=webauthn 이동이 막힌다', async () => {
    const { router } = renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /차단 목록/ }));
    await act(async () => { fireEvent.click(screen.getByText('WebAuthn 탭으로')); });
    expect(screen.getByRole('alertdialog', { name: '저장하지 않은 변경이 있습니다' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=aaguid');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '이동' })); });
    expect(router.state.location.search).toBe('?tab=webauthn');
  });

  it('허용 목록을 비우고 저장하면 L3 — "이해했습니다" 체크 전에는 저장 불가', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [YUBI] });
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: `${YUBI.aaguid} 삭제` }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '모든 등록·인증을 차단할까요?' });
    expect(within(dlg).getByText(/허용 목록\(0개\)/)).toBeInTheDocument();
    const go = within(dlg).getByRole('button', { name: '차단' });
    expect(go).toBeDisabled();
    expect(go).toHaveClass('btn--danger-solid');
    fireEvent.click(within(dlg).getByLabelText('차단된다는 것을 이해했습니다'));
    expect(go).toBeEnabled();
    await act(async () => { fireEvent.click(go); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', { mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
  });

  it('MDS 엄격을 켜는 저장은 L2 영향 요약 — "켜고 저장"', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, mdsStrict: false, entries: [...POLICY.entries] });
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, mdsStrict: true });
    renderTab();
    const sw = await screen.findByRole('switch', { name: 'MDS strict 꺼짐' });
    expect(screen.getByText(/켜면 MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다/)).toBeInTheDocument();
    expect(screen.getByText(/허용 목록 중 MDS 미확인 1개도 차단됩니다/)).toBeInTheDocument();
    fireEvent.click(sw);
    expect(screen.getByText(/MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다/)).toBeInTheDocument();
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: 'MDS 엄격 모드를 켜고 저장할까요?' });
    expect(dlg).toHaveTextContent('MDS 엄격: 꺼짐 → 켜짐');
    expect(dlg).toHaveTextContent('허용 목록 중 MDS 미확인 1개도 차단됩니다');
    expect(dlg).toHaveTextContent('기존 Credential은 유지');
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledWith('t-1', expect.objectContaining({ mdsStrict: true }));
  });

  it('저장 실패는 다이얼로그 안 인라인 오류, SaveBar·입력 유지', async () => {
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /차단 목록/ }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    expect(dlg).toHaveTextContent('방식: 허용 목록 → 차단 목록');
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장' })); });
    expect(within(dlg).getByText('저장하지 못했습니다')).toBeInTheDocument();
    expect(bar()).toHaveTextContent('mode');
    expect(screen.queryByText('저장 실패')).toBeNull();
  });

  it('로드 실패: LoadError + 다시 시도', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce({ ...POLICY, entries: [...POLICY.entries] });
    renderTab();
    expect(await screen.findByText('AAGUID 정책을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('YubiKey 5')).toBeInTheDocument();
  });

  it('저장 중에는 입력이 잠기고 저장은 한 번만 호출된다', async () => {
    let resolve!: (p: AaguidPolicy) => void;
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockReturnValue(new Promise<AaguidPolicy>((r) => { resolve = r; }));
    renderTab();
    fireEvent.click(await screen.findByRole('radio', { name: /차단 목록/ }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 1개를 저장할까요?' });
    const go = within(dlg).getByRole('button', { name: '저장' });
    await act(async () => { fireEvent.click(go); });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '저장 중…' })); });
    expect(aaguidPolicyApi.update).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('textbox', { name: 'AAGUID 입력' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'MDS strict 켜짐' })).toBeDisabled();
    await act(async () => { resolve({ ...POLICY, mode: 'DENYLIST' }); });
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(screen.getByRole('textbox', { name: 'AAGUID 입력' })).toBeEnabled();
  });

  it('모드 원문은 title 툴팁, 저장 확인 변경 목록은 실제로 바뀐 항목만', async () => {
    renderTab();
    const radio = await screen.findByRole('radio', { name: /차단 목록/ });
    expect(within(radio).getByText('차단 목록')).toHaveAttribute('title', 'DENYLIST');
    fireEvent.click(radio);
    fireEvent.click(screen.getByRole('switch', { name: 'MDS strict 켜짐' }));
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: '변경 사항 2개를 저장할까요?' });
    expect(dlg).toHaveTextContent('방식: 허용 목록 → 차단 목록');
    expect(dlg).toHaveTextContent('MDS 엄격: 켜짐 → 꺼짐');
    expect(dlg).not.toHaveTextContent(YUBI.aaguid);
  });

  it('삭제 후 같은 항목을 다시 추가하면 변경 없음 — SaveBar 가 뜨지 않는다', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: `${YUBI.aaguid} 삭제` }));
    expect(bar()).not.toBeNull();
    const input = screen.getByRole('textbox', { name: 'AAGUID 입력' });
    fireEvent.change(input, { target: { value: YUBI.aaguid } });
    fireEvent.click(screen.getByRole('button', { name: '추가' }));
    expect(bar()).toBeNull();
  });

  it('MDS 엄격 영향은 목록과 무관하게 MDS 미등록 모든 인증기 거부로 안내한다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ANY', mdsStrict: false, entries: [] });
    (aaguidPolicyApi.update as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ANY', mdsStrict: true, entries: [] });
    renderTab();
    fireEvent.click(await screen.findByRole('switch', { name: 'MDS strict 꺼짐' }));
    expect(screen.getByText('MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다.')).toBeInTheDocument();
    fireEvent.click(within(bar()!).getByRole('button', { name: '저장…' }));
    const dlg = screen.getByRole('alertdialog', { name: 'MDS 엄격 모드를 켜고 저장할까요?' });
    expect(dlg).toHaveTextContent('MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다.');
    expect(dlg).toHaveTextContent('기존 Credential은 유지');
    expect(dlg).not.toHaveTextContent('현재 목록');
  });
});
