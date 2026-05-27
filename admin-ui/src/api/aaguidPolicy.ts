import { api } from './client';
import type { AaguidPolicyView, AaguidPolicyUpdateRequest, WebauthnConfigDiff, WebauthnDiffRequest } from './types';

export const aaguidPolicyApi = {
  get: (tenantId: string) =>
    api.get<AaguidPolicyView>(`/admin/api/tenants/${tenantId}/aaguid-policy`),
  update: (tenantId: string, body: AaguidPolicyUpdateRequest) =>
    api.put<AaguidPolicyView>(`/admin/api/tenants/${tenantId}/aaguid-policy`, body),
};

export const webauthnDiffApi = {
  diff: (idOrSlug: string, body: WebauthnDiffRequest) =>
    api.post<WebauthnConfigDiff>(`/admin/api/tenants/${idOrSlug}/webauthn-config/diff`, body),
};
