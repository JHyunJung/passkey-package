import { api } from './client';
import type { TenantView, TenantUpdateRequest, WebauthnDiffRequest, WebauthnConfigDiff } from './types';
import type { WebauthnConfig } from './designTypes';

// TenantView fields mapped to WebauthnConfig design type.
// mdsRequired is NOT in WebauthnConfig (not exposed in the tab), so it is
// stored separately and threaded back into update/diff requests.
type ConfigWithMds = WebauthnConfig & { _mdsRequired: boolean };

function adaptConfig(t: TenantView): ConfigWithMds {
  return {
    rpId: t.rpId,
    rpName: t.rpName,
    origins: t.allowedOrigins,
    formats: t.acceptedFormats,
    userVerification: t.requireUserVerification ? 'REQUIRED' : 'PREFERRED',
    attestationConveyance: 'NONE',   // 서버에 없음 — fixture default
    timeoutMs: 60000,                 // 서버에 없음 — fixture default
    _mdsRequired: t.mdsRequired,     // preserved, not user-editable in this tab
  };
}

// 서버 WebauthnConfigDiff.changes → 디자인 diffObjects() 형태({ key, from, to }[]) 로 변환.
// 서버가 array 필드에 대해서는 from/to 대신 added/removed 를 채운다.
function adaptDiffChanges(
  diff: WebauthnConfigDiff,
): { key: string; from: unknown; to: unknown }[] {
  return diff.changes.map((c) => {
    // Array fields (origins / formats): server uses added/removed, not from/to.
    if (Array.isArray(c.added) || Array.isArray(c.removed)) {
      const added = c.added ?? [];
      const removed = c.removed ?? [];
      // Reconstruct from/to arrays so the design's DiffRow array branch fires.
      // from = (current - removed), to = (current + added), but we can synthesise
      // from both lists directly: from = removed, to = added as separate items.
      // The DiffRow code only iterates added/removed diffs, not full arrays, so
      // we can pass them as pseudo arrays that already represent the delta.
      return {
        key: c.field,
        from: removed,    // shown as "-" lines
        to: added,        // shown as "+" lines
      };
    }
    return { key: c.field, from: c.from, to: c.to };
  });
}

export const webauthnApi = {
  get: async (tenantId: string): Promise<ConfigWithMds> => {
    const t = await api.get<TenantView>(`/admin/api/tenants/${tenantId}`);
    return adaptConfig(t);
  },

  update: async (
    tenantId: string,
    displayName: string,
    body: WebauthnConfig,
    mdsRequired: boolean,
  ): Promise<ConfigWithMds> => {
    const updateReq: TenantUpdateRequest = {
      displayName,
      rpId: body.rpId,
      rpName: body.rpName,
      allowedOrigins: body.origins,
      acceptedFormats: body.formats,
      requireUserVerification: body.userVerification === 'REQUIRED',
      mdsRequired,                  // preserve server value, not forced false
    };
    const t = await api.put<TenantView>(`/admin/api/tenants/${tenantId}`, updateReq);
    return adaptConfig(t);
  },

  // diff endpoint returns raw JSON (no ApiResponse envelope) — use postRaw
  diff: async (
    tenantId: string,
    proposed: WebauthnConfig,
    mdsRequired: boolean,
  ): Promise<{ key: string; from: unknown; to: unknown }[]> => {
    const body: WebauthnDiffRequest = {
      rpId: proposed.rpId,
      rpName: proposed.rpName,
      allowedOrigins: proposed.origins,
      acceptedFormats: proposed.formats,
      requireUserVerification: proposed.userVerification === 'REQUIRED',
      mdsRequired,                  // preserve server value
    };
    const raw = await api.postRaw<WebauthnConfigDiff>(
      `/admin/api/tenants/${tenantId}/webauthn-config/diff`,
      body,
    );
    return adaptDiffChanges(raw);
  },
};
