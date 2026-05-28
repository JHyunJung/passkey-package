import { api } from './client';
import type { TenantView, TenantCreateRequest } from './types';
import type { Tenant } from './designTypes';

function adaptTenant(s: TenantView): Tenant {
  return {
    id: s.id,
    name: s.displayName,
    slug: s.slug,
    rpId: s.rpId,
    status: s.status?.toUpperCase() === 'ACTIVE' || s.status === 'active' ? 'ACTIVE' : 'SUSPENDED',
    credentials: s.credentials,
    apiKeys: s.apiKeys,
    lastEventAt: s.lastEventAt,
    createdAt: s.createdAt,
    userVerification: s.requireUserVerification ? 'REQUIRED' : 'PREFERRED',
    attestationConveyance: s.attestationConveyance,
    webauthnTimeoutMs: s.webauthnTimeoutMs,
  };
}

export const tenantsApi = {
  list: async (): Promise<Tenant[]> => {
    const server = await api.get<TenantView[]>('/admin/api/tenants');
    return server.map(adaptTenant);
  },
  get: async (id: string): Promise<Tenant> => {
    const server = await api.get<TenantView>(`/admin/api/tenants/${id}`);
    return adaptTenant(server);
  },
  create: async (input: { name: string; slug: string }): Promise<Tenant> => {
    const body: TenantCreateRequest = {
      slug: input.slug,
      displayName: input.name,
      rpId: input.slug + '.example.com',
      rpName: input.name,
      allowedOrigins: ['https://' + input.slug + '.example.com'],
      acceptedFormats: ['none', 'packed'],
      requireUserVerification: true,
      mdsRequired: false,
      attestationConveyance: 'NONE',
      webauthnTimeoutMs: 60000,
    };
    const server = await api.post<TenantView>('/admin/api/tenants', body);
    return adaptTenant(server);
  },
};
