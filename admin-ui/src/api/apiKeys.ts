import { api } from './client';
import type { ApiKeyView, ApiKeyCreateRequest, ApiKeyCreateResponse, ApiKeyRotateResponse } from './types';
import type { ApiKey } from './designTypes';

// ── Adapter: server ApiKeyView → design ApiKey ────────────────────────────────

function adapt(s: ApiKeyView): ApiKey {
  return {
    id: s.id,
    prefix: s.keyPrefix,
    name: s.name,
    status: s.revokedAt ? 'REVOKED' : 'ACTIVE',
    createdAt: s.createdAt,
    lastUsedAt: s.lastUsedAt ?? null,
    scopes: s.scopes ?? [],
  };
}

// ── API ───────────────────────────────────────────────────────────────────────

export const apiKeysApi = {
  list: async (tenantId: string): Promise<ApiKey[]> => {
    const server = await api.get<ApiKeyView[]>(
      `/admin/api/api-keys?tenantId=${encodeURIComponent(tenantId)}`,
    );
    return server.map(adapt);
  },

  create: async (
    tenantId: string,
    name: string,
    scopes: string[],
  ): Promise<{ key: ApiKey; plaintext: string }> => {
    const body: ApiKeyCreateRequest = { tenantId, name, scopes };
    const res = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', body);
    const key: ApiKey = {
      id: res.id,
      prefix: res.prefix,
      name,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
      lastUsedAt: null,
      scopes: res.scopes ?? scopes,
    };
    return { key, plaintext: res.plainText };
  },

  rotate: async (id: string): Promise<ApiKeyRotateResponse> => {
    return api.post<ApiKeyRotateResponse>(
      `/admin/api/api-keys/${encodeURIComponent(id)}/rotate`, {},
    );
  },

  revoke: async (id: string): Promise<void> => {
    await api.delete<void>(`/admin/api/api-keys/${encodeURIComponent(id)}`);
  },
};
