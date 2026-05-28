import { api } from './client';
import type { ApiKeyView, ApiKeyCreateRequest, ApiKeyCreateResponse } from './types';
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
  ): Promise<{ key: ApiKey; plaintext: string }> => {
    const body: ApiKeyCreateRequest = {
      tenantId,
      name,
      scopes: ['ceremony'],
    };
    // Server returns ApiKeyCreateResponse: { id, plainText, prefix, scopes }
    // plainText = prefix+secret (full plaintext key)
    const res = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', body);

    // Build a minimal ApiKey view from the create response
    // (we do a reload() after this to get the full list with createdAt etc.)
    const key: ApiKey = {
      id: res.id,
      prefix: res.prefix,
      name,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
      lastUsedAt: null,
    };
    return { key, plaintext: res.plainText };
  },

  revoke: async (id: string): Promise<void> => {
    await api.delete<void>(`/admin/api/api-keys/${encodeURIComponent(id)}`);
  },
};
