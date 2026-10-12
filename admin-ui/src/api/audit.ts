import { api } from './client';
import type { AuditLogView } from './types';
import type { AuditEvent, ChainVerifyResult } from './designTypes';
import { LONG_TIMEOUT_MS } from './timeout';

// /admin/api/audit returns ApiResponse<List<AuditLogView>> (envelope)
// /admin/api/audit/chain/verify returns raw TenantVerifyResponse (no envelope)

type TenantVerifyRaw = {
  tenantId: string | null;
  intact: boolean;
  tamperedEntryId: string | null;
  verifiedAt: string;
};

// 현재 서버: ApiResponse<List<AuditLogView>>. §6 ③ 이후 { items, total } 이 올 수 있다.
type AuditListResponse = AuditLogView[] | { items: AuditLogView[]; total?: number };

function adapt(s: AuditLogView): AuditEvent {
  let payload: Record<string, unknown> | null = null;
  if (s.payload) {
    try {
      payload = JSON.parse(s.payload) as Record<string, unknown>;
    } catch {
      payload = { raw: s.payload };
    }
  }
  return {
    id: String(s.id),
    ts: s.createdAt,
    eventType: s.action,
    actorType: s.actorEmail ? 'ADMIN' : 'SYSTEM',
    actorId: s.actorEmail ?? null,
    subjectType: s.targetType ?? null,
    subjectId: s.targetId ?? null,
    payload,
    ...(s.result !== undefined ? { result: s.result } : {}),
  };
}

export const auditApi = {
  list: async (
    tenantId: string,
    page = 0,
    size = 50,
    range?: { from: string; to: string },
  ): Promise<{ items: AuditEvent[]; total?: number; page: number; size: number }> => {
    const q = new URLSearchParams({ tenantId, page: String(page), size: String(size) });
    // 서버 AuditLogController.list: from/to = ISO-8601 OffsetDateTime
    if (range) {
      q.set('from', range.from);
      q.set('to', range.to);
    }
    const res = await api.get<AuditListResponse>(`/admin/api/audit?${q}`);
    const rows = Array.isArray(res) ? res : res.items;
    const total = Array.isArray(res) ? undefined : res.total;
    return { items: rows.map(adapt), ...(total !== undefined ? { total } : {}), page, size };
  },

  verify: async (tenantId: string, from?: string, to?: string): Promise<ChainVerifyResult> => {
    // /admin/api/audit/chain/verify returns raw JSON (no ApiResponse envelope)
    let windowHours = 24;
    if (from && to) {
      const diffMs = new Date(to).getTime() - new Date(from).getTime();
      if (diffMs > 0) {
        windowHours = Math.max(1, Math.min(168, Math.round(diffMs / 3_600_000)));
      }
    }
    const url = `/admin/api/audit/chain/verify?tenantId=${encodeURIComponent(tenantId)}&windowHours=${windowHours}`;
    const s = await api.getRaw<TenantVerifyRaw>(url, { timeoutMs: LONG_TIMEOUT_MS });
    // getRaw returns the raw JSON — check for error envelopes (non-2xx responses
    // return an object with 'success: false' or just an error shape)
    if (!s || typeof s !== 'object') {
      throw new Error('Unexpected response from chain/verify');
    }
    const raw = s as Record<string, unknown>;
    if ('success' in raw && raw.success === false) {
      throw new Error(String(raw['message'] ?? 'chain/verify 실패'));
    }
    return {
      intact: s.intact,
      verifiedRows: 0, // not in server response — overview endpoint has this (Phase E3)
      tamperedEntryIds: s.tamperedEntryId ? [s.tamperedEntryId] : [],
      verifiedAt: s.verifiedAt,
    };
  },
};
