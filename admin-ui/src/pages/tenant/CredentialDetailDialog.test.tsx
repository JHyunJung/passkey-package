import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import CredentialDetailDialog from './CredentialDetailDialog';
import { credentialsApi } from '@/api/credentials';
import type { Credential } from '@/api/designTypes';

const cred: Credential = {
  credentialId: 'cred-abc-123456789012',
  externalUserId: 'user-handle-1',
  nickname: 'My Phone',
  authenticatorName: 'iCloud Keychain',
  status: 'ACTIVE',
  aaguid: 'aaguid-x',
  transports: ['internal', 'hybrid'],
  signatureCounter: 5,
  lastUsedAt: '2026-06-07T05:00:00Z',
  createdAt: '2026-06-01T05:00:00Z',
  attestationFormat: 'packed',
};

describe('CredentialDetailDialog', () => {
  afterEach(() => vi.restoreAllMocks());

  it('renders credential detail immediately from props', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    // 상세는 추가 호출 없이 즉시(동기) 렌더된다
    expect(screen.getByText('user-handle-1')).toBeInTheDocument();
    expect(screen.getByText('My Phone')).toBeInTheDocument();
    expect(screen.getByText('packed')).toBeInTheDocument();
    // authEvents promise 해소까지 flush — act() 경고 방지 + 인접 테스트 누수 차단
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });

  it('섹션(식별 정보·인증기·인증 기록)은 구분선으로 나뉜 dlg-sec', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const { baseElement } = render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    expect(baseElement.querySelectorAll('.dlg-sec')).toHaveLength(3);
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });

  it('shows auth events after load', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([
      { result: 'SUCCESS', failureReason: null, signCount: 5, createdAt: '2026-06-07T05:00:00Z' },
      { result: 'FAILED', failureReason: 'SIGN_COUNT_REPLAY', signCount: 5, createdAt: '2026-06-06T05:00:00Z' },
    ]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText('성공')).toBeInTheDocument());
    expect(screen.getByText('실패')).toBeInTheDocument();
    expect(screen.getByText('SIGN_COUNT_REPLAY')).toBeInTheDocument();
  });

  it('shows empty state when no events', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText(/아직 인증 이력이 없습니다/)).toBeInTheDocument());
  });

  it('shows error state with retry when load fails', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockRejectedValue(new Error('boom'));
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /재시도/ })).toBeInTheDocument();
  });

  it('refetches and renders events when retry is clicked after a failure', async () => {
    const spy = vi.spyOn(credentialsApi, 'authEvents')
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce([
        { result: 'SUCCESS', failureReason: null, signCount: 5, createdAt: '2026-06-07T05:00:00Z' },
        { result: 'FAILED', failureReason: 'SIGN_COUNT_REPLAY', signCount: 5, createdAt: '2026-06-06T05:00:00Z' },
      ]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);

    const retry = await screen.findByRole('button', { name: /재시도/ });
    expect(spy).toHaveBeenCalledTimes(1);

    fireEvent.click(retry);

    await waitFor(() => expect(screen.getByText('성공')).toBeInTheDocument());
    expect(spy).toHaveBeenCalledTimes(2);
    expect(screen.getByText('실패')).toBeInTheDocument();
    expect(screen.getByText('SIGN_COUNT_REPLAY')).toBeInTheDocument();
  });

  it('position 이 있으면 위치와 이전/다음 버튼, 핸들러가 없으면 비활성', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const onNext = vi.fn();
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} position={{ index: 3, total: 120 }} onNext={onNext} />);
    expect(screen.getByText('3 / 120')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이전 credential' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다음 credential' }));
    expect(onNext).toHaveBeenCalledTimes(1);
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });

  it('position 이 없으면 이동 UI 를 렌더하지 않는다', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    expect(screen.queryByRole('button', { name: '이전 credential' })).toBeNull();
    expect(screen.queryByRole('button', { name: '다음 credential' })).toBeNull();
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });

  it('c 가 바뀌면(이전/다음) 그 credential 의 인증 기록을 다시 불러온다', async () => {
    const spy = vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const { rerender } = render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} position={{ index: 1, total: 2 }} />);
    await screen.findByText(/아직 인증 이력이 없습니다/);
    rerender(<CredentialDetailDialog c={{ ...cred, credentialId: 'cred-next-000000000000', externalUserId: 'user-handle-2' }} tenantId="t1" onClose={() => {}} position={{ index: 2, total: 2 }} />);
    expect(screen.getByText('user-handle-2')).toBeInTheDocument();
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith('t1', 'cred-next-000000000000', 20));
  });

  it('섹션 3개와 푸터 좌측 회수 버튼(ACTIVE + onRevoke)', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    const onRevoke = vi.fn();
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} onRevoke={onRevoke} />);
    // '인증기' 는 섹션 제목과 dt 둘 다 있으므로 섹션 제목(.t-over)으로 한정
    for (const s of ['식별 정보', '인증기', '인증 기록 (최근 20건)']) expect(screen.getByText(s, { selector: '.t-over' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Credential 회수…' }));
    expect(onRevoke).toHaveBeenCalledWith(cred);
    await screen.findByText(/아직 인증 이력이 없습니다/);
  });
});
