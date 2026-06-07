import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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

  it('renders credential detail immediately from props', () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    expect(screen.getByText('user-handle-1')).toBeInTheDocument();
    expect(screen.getByText('My Phone')).toBeInTheDocument();
    expect(screen.getByText('packed')).toBeInTheDocument();
  });

  it('shows auth events after load', async () => {
    vi.spyOn(credentialsApi, 'authEvents').mockResolvedValue([
      { result: 'SUCCESS', failureReason: null, signCount: 5, createdAt: '2026-06-07T05:00:00Z' },
      { result: 'FAILED', failureReason: 'SIGN_COUNT_REPLAY', signCount: 5, createdAt: '2026-06-06T05:00:00Z' },
    ]);
    render(<CredentialDetailDialog c={cred} tenantId="t1" onClose={() => {}} />);
    await waitFor(() => expect(screen.getByText('SUCCESS')).toBeInTheDocument());
    expect(screen.getByText('FAILED')).toBeInTheDocument();
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
});
