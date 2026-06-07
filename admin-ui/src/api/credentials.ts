import { api } from './client';
import type { CredentialView, PageView, AuthEventView } from './types';
import type { Credential, AuthEvent } from './designTypes';

// CredentialView (서버) → Credential (디자인) 어댑터
// transports: 서버는 comma-separated string, 디자인은 string[]
function adapt(s: CredentialView): Credential {
  return {
    credentialId: s.credentialId,
    externalUserId: s.userHandle,
    nickname: s.label ?? null,                       // 사용자 별칭(label)
    authenticatorName: s.authenticatorName ?? null,  // MDS 룩업(모델/상태)
    status: 'ACTIVE',  // 서버 CredentialView에 status 필드 없음 → 기본 ACTIVE
    aaguid: s.aaguidHex ?? null,
    transports: s.transports ? s.transports.split(',').map((t) => t.trim()).filter(Boolean) : [],
    signatureCounter: s.signCount,
    lastUsedAt: s.lastUsedAt ?? null,
    createdAt: s.createdAt,
    attestationFormat: s.attestationFormat ?? null,
  };
}

export const credentialsApi = {
  list: async (
    tenantId: string,
    page = 0,
    size = 50,
    search?: string,
  ): Promise<{ items: Credential[]; total: number; page: number; size: number }> => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (search) q.set('search', search);
    const res = await api.get<PageView<CredentialView>>(
      `/admin/api/tenants/${tenantId}/credentials?${q}`,
    );
    return {
      items: res.content.map(adapt),
      total: res.totalElements,
      page: res.page,
      size: res.size,
    };
  },

  revoke: async (tenantId: string, credentialId: string): Promise<void> => {
    await api.delete<void>(`/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}`);
  },

  authEvents: async (
    tenantId: string,
    credentialId: string,
    size = 20,
  ): Promise<AuthEvent[]> => {
    const q = new URLSearchParams({ page: '0', size: String(size) });
    const res = await api.get<PageView<AuthEventView>>(
      `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}/auth-events?${q}`,
    );
    return res.content.map((e) => ({
      result: e.result === 'SUCCESS' ? 'SUCCESS' : 'FAILED',
      failureReason: e.failureReason ?? null,
      signCount: e.signCount,
      createdAt: e.createdAt,
    }));
  },
};
