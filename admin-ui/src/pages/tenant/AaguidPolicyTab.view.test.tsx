// admin-ui/src/pages/tenant/AaguidPolicyTab.view.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, within, act, waitFor, cleanup } from '@testing-library/react';
import { useNavigate } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';

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

describe('AaguidPolicyTab V2', () => {
  it('mode radiogroup, MDS 확인/미확인 배지, 탭 점 사유 Alert', async () => {
    renderTab();
    const group = await screen.findByRole('radiogroup', { name: 'mode' });
    expect(within(group).getAllByRole('radio')).toHaveLength(3);
    expect(within(group).getByRole('radio', { name: /허용 목록/ })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('YubiKey 5')).toBeInTheDocument();
    expect(screen.getByText('MDS 확인')).toHaveClass('badge--success');
    expect(screen.getByText('MDS 에 없음')).toBeInTheDocument();
    expect(screen.getByText('미확인')).toHaveClass('badge--warning');
    expect(screen.getByText(/허용 목록에 MDS 에서 확인되지 않은 AAGUID 가 1건 있습니다/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'MDS strict 켜짐' })).toBeChecked();
    expect(bar()).toBeNull();
  });

  it('미확인 항목이 없으면 Alert 가 없다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ ...POLICY, entries: [YUBI] });
    renderTab();
    await screen.findByText('YubiKey 5');
    expect(screen.queryByText(/탭 점 배지 사유/)).toBeNull();
  });

  it('추가: 자동 포맷 → + 추가 → SaveBar → 저장 시 기존과 같은 payload, onSaved 호출', async () => {
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
    await act(async () => { fireEvent.click(within(bar()!).getByRole('button', { name: '저장' })); });
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

  it('빈 허용 목록 문구', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ALLOWLIST', mdsStrict: false, entries: [] });
    renderTab();
    expect(await screen.findByText('비어 있음 — 이 상태에서는 모든 ceremony가 차단됩니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /차단 목록/ }));
    expect(screen.getByText('비어 있음 — 이 상태에서는 모든 ceremony가 허용됩니다.')).toBeInTheDocument();
    expect(screen.getByText('차단된 AAGUID')).toBeInTheDocument();
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
});
